package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.scoring_service.client.ExamGenerationView;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.dto.ConvocationDTO;
import tn.epos.scoring_service.dto.EnvoiConvocationsResult;
import tn.epos.scoring_service.entities.Etudiant;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;
import tn.epos.scoring_service.service.email.ConvocationEmailService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * #227 — la dérivation des convocations et leur envoi.
 *
 * <p>Ces tests pinnent la RÈGLE MÉTIER (heure d'arrivée par vague, reprise à
 * heureDebut chaque jour, ordre du listing) désormais détenue par le backend :
 * c'est elle que l'écran web ET l'e-mail de l'étudiant consomment, donc elle ne
 * doit plus pouvoir bouger sans qu'un test le dise.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConvocationService - convocations (#227)")
class ConvocationServiceTest {

    private static final Long EXAM = 50L;
    private static final LocalDate JOUR1 = LocalDate.of(2026, Month.JULY, 28);
    private static final LocalDate JOUR2 = LocalDate.of(2026, Month.JULY, 29);

    @Mock private IExamenParticipationRepository participationRepository;
    @Mock private ExamServiceClient examServiceClient;
    @Mock private ConvocationEmailService emailService;

    private ConvocationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-28T08:00:00Z"), ZoneId.of("Africa/Tunis"));
        service = new ConvocationService(participationRepository, examServiceClient,
                emailService, clock);
    }

    /** Examen à 2 stations de 10 min → un circuit dure 20 min. */
    private ExamGenerationView exam(LocalDate date) {
        List<ExamGenerationView.StationView> stations = List.of(
                new ExamGenerationView.StationView(1L, 1, List.of(100L)),
                new ExamGenerationView.StationView(2L, 2, List.of(101L)));
        return new ExamGenerationView(EXAM, "EPOS Biologie", date, LocalTime.of(9, 0),
                null, 10, 0, 3, "CONFIGURE", stations);
    }

    private Lot lot(long id, int numero, LocalDate jour) {
        Lot l = new Lot();
        l.setId(id);
        l.setNumeroLot(numero);
        l.setJour(jour);
        return l;
    }

    private ExamenParticipation part(long id, Lot lot, Integer ordre, String nom, String email) {
        Etudiant e = new Etudiant();
        e.setId(id * 10);
        e.setNom(nom);
        e.setPrenom("P" + nom);
        e.setNumero_inscription("N-" + id);
        e.setEmail(email);
        ExamenParticipation p = new ExamenParticipation();
        p.setId(id);
        p.setExamen_id(EXAM);
        p.setEtudiant(e);
        p.setLot(lot);
        p.setOrdre_import(ordre);
        return p;
    }

    @Nested
    @DisplayName("construire()")
    class Construire {

        @Test
        @DisplayName("L'heure de la vague décale d'un circuit complet par lot")
        void heureDeVague_devraitDecalerParCircuit() {
            Lot l1 = lot(1L, 1, null);
            Lot l2 = lot(2L, 2, null);
            when(examServiceClient.getExamForGeneration(EXAM)).thenReturn(exam(JOUR1));
            when(participationRepository.findByExamenId(EXAM)).thenReturn(List.of(
                    part(1L, l1, 1, "Zouari", "z@etu.tn"),
                    part(2L, l2, 2, "Amri", "a@etu.tn")));

            List<ConvocationDTO> out = service.construire(EXAM);

            // 2 stations × 10 min = 20 min de circuit.
            assertThat(out.get(0).heureConvocation()).isEqualTo("09:00");
            assertThat(out.get(1).heureConvocation()).isEqualTo("09:20");
        }

        @Test
        @DisplayName("#147 — les arrivées repartent de heureDebut CHAQUE jour")
        void multiJour_devraitRepartirDeHeureDebut() {
            Lot l1 = lot(1L, 1, JOUR1);
            Lot l2 = lot(2L, 2, JOUR2); // lot 2 déplacé au lendemain
            when(examServiceClient.getExamForGeneration(EXAM)).thenReturn(exam(JOUR1));
            when(participationRepository.findByExamenId(EXAM)).thenReturn(List.of(
                    part(1L, l1, 1, "Zouari", "z@etu.tn"),
                    part(2L, l2, 2, "Amri", "a@etu.tn")));

            List<ConvocationDTO> out = service.construire(EXAM);

            assertThat(out.get(0).jour()).isEqualTo(JOUR1);
            assertThat(out.get(0).heureConvocation()).isEqualTo("09:00");
            // PAS 09:20 : le lot 2 ouvre sa propre journée, il ne fait pas la
            // queue derrière une vague de la veille.
            assertThat(out.get(1).jour()).isEqualTo(JOUR2);
            assertThat(out.get(1).heureConvocation()).isEqualTo("09:00");
        }

        @Test
        @DisplayName("#256 — ordre du listing, jamais l'alphabet")
        void ordre_devraitSuivreLeListing() {
            Lot l1 = lot(1L, 1, null);
            when(examServiceClient.getExamForGeneration(EXAM)).thenReturn(exam(JOUR1));
            when(participationRepository.findByExamenId(EXAM)).thenReturn(List.of(
                    part(1L, l1, 3, "Abidi", "ab@etu.tn"),   // 1er alphabétiquement
                    part(2L, l1, 1, "Zouari", "z@etu.tn"),   // 1er dans le fichier
                    part(3L, l1, 2, "Trabelsi", "t@etu.tn")));

            List<ConvocationDTO> out = service.construire(EXAM);

            assertThat(out).extracting(ConvocationDTO::nom)
                    .containsExactly("Zouari", "Trabelsi", "Abidi");
        }

        @Test
        @DisplayName("Étudiant sans lot : pas de convocation inventée")
        void sansLot_devraitEtreExclu() {
            when(examServiceClient.getExamForGeneration(EXAM)).thenReturn(exam(JOUR1));
            when(participationRepository.findByExamenId(EXAM)).thenReturn(List.of(
                    part(1L, null, 1, "SansLot", "s@etu.tn")));

            assertThat(service.construire(EXAM)).isEmpty();
        }
    }

    @Nested
    @DisplayName("envoyer()")
    class Envoyer {

        @Test
        @DisplayName("Sans adresse : compté à part, ce n'est PAS un échec")
        void sansAdresse_devraitEtreComptePart() {
            Lot l1 = lot(1L, 1, null);
            when(examServiceClient.getExamForGeneration(EXAM)).thenReturn(exam(JOUR1));
            when(participationRepository.findByExamenId(EXAM)).thenReturn(List.of(
                    part(1L, l1, 1, "Zouari", "z@etu.tn"),
                    part(2L, l1, 2, "Amri", ""),
                    part(3L, l1, 3, "Dridi", null)));
            when(emailService.estSimule()).thenReturn(true);

            EnvoiConvocationsResult r = service.envoyer(EXAM);

            assertThat(r.total()).isEqualTo(3);
            assertThat(r.envoyes()).isEqualTo(1);
            assertThat(r.sansAdresse()).isEqualTo(2);
            assertThat(r.echecs()).isZero();
            assertThat(r.lignes()).extracting(EnvoiConvocationsResult.EnvoiLigne::statut)
                    .containsExactly("ENVOYE", "SANS_ADRESSE", "SANS_ADRESSE");
        }

        @Test
        @DisplayName("Un destinataire en échec n'interrompt pas les autres")
        void unEchec_neDoitPasArreterLEnvoi() {
            Lot l1 = lot(1L, 1, null);
            when(examServiceClient.getExamForGeneration(EXAM)).thenReturn(exam(JOUR1));
            when(participationRepository.findByExamenId(EXAM)).thenReturn(List.of(
                    part(1L, l1, 1, "Zouari", "z@etu.tn"),
                    part(2L, l1, 2, "Amri", "a@etu.tn"),
                    part(3L, l1, 3, "Dridi", "d@etu.tn")));
            doNothing().when(emailService).envoyerConvocation(any(), anyString());
            doThrow(new RuntimeException("boîte pleine"))
                    .when(emailService).envoyerConvocation(
                            argThat(c -> c != null && "a@etu.tn".equals(c.email())), anyString());

            EnvoiConvocationsResult r = service.envoyer(EXAM);

            assertThat(r.envoyes()).isEqualTo(2);
            assertThat(r.echecs()).isEqualTo(1);
            assertThat(r.lignes().get(1).statut()).isEqualTo("ECHEC");
            assertThat(r.lignes().get(1).message()).contains("boîte pleine");
            // Les deux autres sont bien partis.
            assertThat(r.lignes().get(0).statut()).isEqualTo("ENVOYE");
            assertThat(r.lignes().get(2).statut()).isEqualTo("ENVOYE");
        }

        @Test
        @DisplayName("La date d'envoi n'est marquée QUE pour les envois réussis")
        void dateEnvoi_devraitMarquerSeulementLesReussis() {
            Lot l1 = lot(1L, 1, null);
            ExamenParticipation ok = part(1L, l1, 1, "Zouari", "z@etu.tn");
            ExamenParticipation sansMail = part(2L, l1, 2, "Amri", "");
            when(examServiceClient.getExamForGeneration(EXAM)).thenReturn(exam(JOUR1));
            when(participationRepository.findByExamenId(EXAM)).thenReturn(List.of(ok, sansMail));

            service.envoyer(EXAM);

            assertThat(ok.getConvocation_envoyee_a()).isNotNull();
            assertThat(sansMail.getConvocation_envoyee_a()).isNull();
            verify(participationRepository).saveAll(argThat((List<ExamenParticipation> l) ->
                    new ArrayList<>(l).size() == 1));
        }

        @Test
        @DisplayName("Mode simulé : le bilan le DIT, il n'annonce pas un envoi réel")
        void modeSimule_devraitEtreAnnonce() {
            Lot l1 = lot(1L, 1, null);
            when(examServiceClient.getExamForGeneration(EXAM)).thenReturn(exam(JOUR1));
            when(participationRepository.findByExamenId(EXAM)).thenReturn(List.of(
                    part(1L, l1, 1, "Zouari", "z@etu.tn")));
            when(emailService.estSimule()).thenReturn(true);

            assertThat(service.envoyer(EXAM).simule()).isTrue();
        }
    }
}
