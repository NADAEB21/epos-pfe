package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.client.ExamGenerationView;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.dto.ParticipationDTO;
import tn.epos.scoring_service.dto.PresenceResult;
import tn.epos.scoring_service.dto.RepartitionResult;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.entities.LotStatus;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;
import tn.epos.scoring_service.repositories.ILotRepository;
import tn.epos.scoring_service.repositories.IStudentGroupRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LotAssignmentService - répartition (Phase 1) + présence (Phase 2)")
class LotAssignmentServiceTest {

    @Mock private ExamServiceClient examServiceClient;
    @Mock private IExamenParticipationRepository participationRepository;
    @Mock private ILotRepository lotRepository;
    @Mock private IStudentGroupRepository studentGroupRepository;

    @InjectMocks private LotAssignmentService service;

    private static final long EXAM_ID = 100L;

    @BeforeEach
    void setUp() {
        // Assign ids to saved lots so the result/details carry them.
        AtomicLong lotSeq = new AtomicLong(1);
        lenient().when(lotRepository.save(any(Lot.class))).thenAnswer(inv -> {
            Lot l = inv.getArgument(0);
            if (l.getId() == null) l.setId(lotSeq.getAndIncrement());
            return l;
        });
        lenient().when(participationRepository.save(any(ExamenParticipation.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // No prior lots by default.
        lenient().when(lotRepository.findByExamenId(EXAM_ID)).thenReturn(List.of());
    }

    private ExamGenerationView exam(String statut, int nbStations, Integer capacite) {
        List<ExamGenerationView.StationView> stations = new ArrayList<>();
        for (int i = 0; i < nbStations; i++) {
            stations.add(new ExamGenerationView.StationView(10L + i, i + 1, List.of(1000L + i)));
        }
        return new ExamGenerationView(EXAM_ID, "Examen test", LocalDate.of(2026, 6, 20),
                LocalTime.of(9, 0), null, 15, 0, capacite, statut, stations);
    }

    private List<ExamenParticipation> enrolled(int count) {
        List<ExamenParticipation> list = new ArrayList<>();
        for (long i = 1; i <= count; i++) {
            ExamenParticipation p = new ExamenParticipation();
            p.setId(i);
            p.setExamen_id(EXAM_ID);
            list.add(p);
        }
        return list;
    }

    @Nested
    @DisplayName("Répartition en lots (CONFIGURE)")
    class Repartition {

        /**
         * #256 — RÉÉCRIT, pas restauré : la version #164 de ce test exigeait l'ÉQUILIBRAGE
         * (54 → 4×11 + 1×10). Décision encadrant (2026-07-22, confirmée par Nada le
         * 2026-07-24) : les lots SUIVENT le listing, blocs pleins, dernier lot = le reste.
         * #164 égalisait la charge des stations ; l'usage réel veut un listing lisible et
         * un petit dernier lot comme place de repli (retardataire/excusé). Ne pas
         * « re-corriger » vers l'équilibrage.
         */
        @Test
        @DisplayName("#256 : K=3, cap=4 → lotSize 12 ; 54 inscrits → 4×12 + 1×6 (séquentiel)")
        void repartit_54en5lots() {
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(exam("CONFIGURE", 3, 4));
            when(participationRepository.findByExamenId(EXAM_ID)).thenReturn(enrolled(54));

            RepartitionResult r = service.repartir(EXAM_ID);

            assertThat(r.lotSize()).isEqualTo(12);
            assertThat(r.lots()).isEqualTo(5);
            assertThat(r.etudiantsRepartis()).isEqualTo(54);
            assertThat(r.details()).hasSize(5);
            assertThat(r.details().subList(0, 4)).allMatch(d -> d.taille() == 12);  // blocs PLEINS
            assertThat(r.details().get(4).taille()).isEqualTo(6);                    // le reste
            assertThat(r.details().get(0).numeroLot()).isEqualTo(1);
        }

        /**
         * #256 — LE cas que l'encadrant a tranché : 15 inscrits, lotSize 12. #164 refusait
         * « 12 + 3 » ; c'est désormais exactement ce qui est VOULU (« le dernier lot peut
         * n'avoir que 3 étudiants, c'est mieux que 18/17/19 » — la place de repli).
         */
        @Test
        @DisplayName("#256 : 15 inscrits, lotSize 12 → 12 + 3 (le petit dernier lot est VOULU)")
        void repartit_sequentiel_15en2lots() {
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(exam("CONFIGURE", 3, 4));
            when(participationRepository.findByExamenId(EXAM_ID)).thenReturn(enrolled(15));

            RepartitionResult r = service.repartir(EXAM_ID);

            assertThat(r.lots()).isEqualTo(2);
            assertThat(r.details().get(0).taille()).isEqualTo(12);
            assertThat(r.details().get(1).taille()).isEqualTo(3);
            assertThat(r.details().stream().mapToInt(RepartitionResult.LotInfo::taille).sum()).isEqualTo(15);
        }

        /**
         * #256 — la SOURCE de l'ordre : le listing importé (ordre_import), jamais l'id
         * technique. Ici les ids sont à REBOURS du fichier : le lot 1 doit contenir les
         * premières LIGNES du fichier, pas les premiers ids. Les ajouts manuels
         * (ordre_import null) passent après le fichier.
         */
        @Test
        @DisplayName("#256 : le lot 1 suit l'ordre du FICHIER, pas l'ordre des ids ; ajouts manuels après")
        void repartit_suitLOrdreDuListing() {
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(exam("CONFIGURE", 1, 2)); // lotSize 2
            List<ExamenParticipation> inscrits = new ArrayList<>();
            // id 1 = DERNIÈRE ligne du fichier ; id 3 = première ; id 2 = ajout manuel (null)
            ExamenParticipation a = new ExamenParticipation(); a.setId(1L); a.setOrdre_import(3);
            ExamenParticipation b = new ExamenParticipation(); b.setId(2L); b.setOrdre_import(null);
            ExamenParticipation c = new ExamenParticipation(); c.setId(3L); c.setOrdre_import(1);
            inscrits.add(a); inscrits.add(b); inscrits.add(c);
            when(participationRepository.findByExamenId(EXAM_ID)).thenReturn(inscrits);

            service.repartir(EXAM_ID);

            // lot 1 (taille 2) = lignes 1 et 3 du fichier (ids 3 puis 1) ; l'ajout manuel
            // (id 2) tombe dans le lot 2.
            assertThat(c.getLot().getNumeroLot()).isEqualTo(1);
            assertThat(a.getLot().getNumeroLot()).isEqualTo(1);
            assertThat(b.getLot().getNumeroLot()).isEqualTo(2);
        }

        @Test
        @DisplayName("Chaque participation reçoit son lot (participation.lot câblé)")
        void repartit_cableLeLotSurChaqueParticipation() {
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(exam("CONFIGURE", 2, 4));
            when(participationRepository.findByExamenId(EXAM_ID)).thenReturn(enrolled(8)); // lotSize 8 → 1 lot

            service.repartir(EXAM_ID);

            ArgumentCaptor<ExamenParticipation> captor = ArgumentCaptor.forClass(ExamenParticipation.class);
            verify(participationRepository, atLeastOnce()).save(captor.capture());
            assertThat(captor.getAllValues()).hasSize(8);
            assertThat(captor.getAllValues()).allMatch(p -> p.getLot() != null);
            assertThat(captor.getAllValues()).allMatch(p -> p.getLot().getStatut() == LotStatus.EN_ATTENTE);
        }

        @Test
        @DisplayName("Statut ≠ CONFIGURE → BusinessException, aucun lot créé")
        void repartit_rejetteHorsConfigure() {
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(exam("EN_COURS", 3, 4));

            assertThatThrownBy(() -> service.repartir(EXAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("CONFIGURE");

            verify(lotRepository, never()).save(any());
        }

        @Test
        @DisplayName("Aucune station → BusinessException")
        void repartit_rejetteSansStation() {
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(exam("CONFIGURE", 0, 4));

            assertThatThrownBy(() -> service.repartir(EXAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("station");
        }

        @Test
        @DisplayName("Aucun inscrit → BusinessException")
        void repartit_rejetteSansInscrit() {
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(exam("CONFIGURE", 3, 4));
            when(participationRepository.findByExamenId(EXAM_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> service.repartir(EXAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("inscrit");
        }

        @Test
        @DisplayName("Re-run : purge les lots existants et détache les participations avant de repartir")
        void repartit_purgeAvantRecalcul() {
            Lot ancien = new Lot();
            ancien.setId(7L);
            ancien.setExamenId(EXAM_ID);
            ExamenParticipation ancienneP = new ExamenParticipation();
            ancienneP.setId(99L);
            ancienneP.setLot(ancien);

            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(exam("CONFIGURE", 2, 4));
            when(participationRepository.findByExamenId(EXAM_ID)).thenReturn(enrolled(4));
            when(lotRepository.findByExamenId(EXAM_ID)).thenReturn(List.of(ancien));
            when(participationRepository.findByLotId(7L)).thenReturn(new ArrayList<>(List.of(ancienneP)));
            when(studentGroupRepository.findByLotId(7L)).thenReturn(List.of());

            service.repartir(EXAM_ID);

            // The old participation was detached (lot nulled) and the old lot deleted.
            assertThat(ancienneP.getLot()).isNull();
            verify(lotRepository).deleteAll(List.of(ancien));
        }
    }

    @Nested
    @DisplayName("Présence par lot (jour J)")
    class Presence {

        private Lot lot() {
            Lot l = new Lot();
            l.setId(5L);
            l.setExamenId(EXAM_ID);
            l.setNumeroLot(1);
            l.setStatut(LotStatus.EN_ATTENTE);
            return l;
        }

        @Test
        @DisplayName("Sans absents → tout le lot présent, statut → EN_COURS")
        void presence_toutPresentParDefaut() {
            Lot lot = lot();
            when(lotRepository.findById(5L)).thenReturn(Optional.of(lot));
            when(participationRepository.findByLotId(5L)).thenReturn(enrolled(6));

            PresenceResult r = service.markPresence(5L, null);

            assertThat(r.total()).isEqualTo(6);
            assertThat(r.presents()).isEqualTo(6);
            assertThat(r.absents()).isZero();
            assertThat(lot.getStatut()).isEqualTo(LotStatus.EN_COURS);
        }

        @Test
        @DisplayName("Avec liste d'absents → ceux-là est_present=false, le reste true")
        void presence_marqueAbsents() {
            Lot lot = lot();
            List<ExamenParticipation> parts = enrolled(6);
            when(lotRepository.findById(5L)).thenReturn(Optional.of(lot));
            when(participationRepository.findByLotId(5L)).thenReturn(parts);

            PresenceResult r = service.markPresence(5L, List.of(2L, 4L));

            assertThat(r.presents()).isEqualTo(4);
            assertThat(r.absents()).isEqualTo(2);
            assertThat(parts).filteredOn(p -> List.of(2L, 4L).contains(p.getId()))
                    .allMatch(p -> Boolean.FALSE.equals(p.getEst_present()));
            assertThat(parts).filteredOn(p -> !List.of(2L, 4L).contains(p.getId()))
                    .allMatch(p -> Boolean.TRUE.equals(p.getEst_present()));
        }

        @Test
        @DisplayName("Lot introuvable → BusinessException")
        void presence_rejetteSiLotIntrouvable() {
            when(lotRepository.findById(5L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.markPresence(5L, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("introuvable");
        }

        @Test
        @DisplayName("Lot sans participation → BusinessException")
        void presence_rejetteSiLotVide() {
            when(lotRepository.findById(5L)).thenReturn(Optional.of(lot()));
            when(participationRepository.findByLotId(5L)).thenReturn(List.of());

            assertThatThrownBy(() -> service.markPresence(5L, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("aucun étudiant");
        }
    }

    @Nested
    @DisplayName("Déplacement manuel d'un étudiant entre lots (#165, CONFIGURE)")
    class Deplacement {

        private Lot lot(long id) {
            Lot l = new Lot();
            l.setId(id);
            l.setExamenId(EXAM_ID);
            l.setNumeroLot((int) id);
            l.setStatut(LotStatus.EN_ATTENTE);
            return l;
        }

        private ExamenParticipation participation(long id, Lot lot) {
            ExamenParticipation p = new ExamenParticipation();
            p.setId(id);
            p.setExamen_id(EXAM_ID);
            p.setLot(lot);
            return p;
        }

        @Test
        @DisplayName("Happy path : re-pointe le lot et recalcule tailleLot des deux lots")
        void deplace_repointeEtRecalcule() {
            Lot source = lot(10L);
            Lot target = lot(20L);
            ExamenParticipation p = participation(1L, source);

            when(participationRepository.findById(1L)).thenReturn(Optional.of(p));
            when(lotRepository.findById(20L)).thenReturn(Optional.of(target));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(exam("CONFIGURE", 3, 4));
            // après le move : le lot cible compte 3 membres, la source 2
            when(participationRepository.findByLotId(20L)).thenReturn(enrolled(3));
            when(participationRepository.findByLotId(10L)).thenReturn(enrolled(2));

            ParticipationDTO dto = service.deplacerEtudiant(20L, 1L);

            assertThat(p.getLot()).isSameAs(target);
            assertThat(dto.lotId()).isEqualTo(20L);
            assertThat(target.getTailleLot()).isEqualTo(3);
            assertThat(source.getTailleLot()).isEqualTo(2);
            verify(participationRepository).save(p);
        }

        @Test
        @DisplayName("Lot cible dans un autre examen → BusinessException, aucun déplacement")
        void deplace_rejetteCrossExam() {
            Lot source = lot(10L);
            Lot target = lot(20L);
            target.setExamenId(999L); // autre examen
            ExamenParticipation p = participation(1L, source);

            when(participationRepository.findById(1L)).thenReturn(Optional.of(p));
            when(lotRepository.findById(20L)).thenReturn(Optional.of(target));

            assertThatThrownBy(() -> service.deplacerEtudiant(20L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("autre examen");

            assertThat(p.getLot()).isSameAs(source); // inchangé
            verify(participationRepository, never()).save(any());
        }

        @Test
        @DisplayName("Examen hors CONFIGURE → BusinessException, aucun déplacement")
        void deplace_rejetteHorsConfigure() {
            Lot source = lot(10L);
            Lot target = lot(20L);
            ExamenParticipation p = participation(1L, source);

            when(participationRepository.findById(1L)).thenReturn(Optional.of(p));
            when(lotRepository.findById(20L)).thenReturn(Optional.of(target));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(exam("EN_COURS", 3, 4));

            assertThatThrownBy(() -> service.deplacerEtudiant(20L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("CONFIGURE");

            assertThat(p.getLot()).isSameAs(source);
            verify(participationRepository, never()).save(any());
        }

        @Test
        @DisplayName("Participation introuvable → ResourceNotFoundException (404)")
        void deplace_participationIntrouvable() {
            when(participationRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deplacerEtudiant(20L, 1L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Lot cible introuvable → ResourceNotFoundException (404)")
        void deplace_lotCibleIntrouvable() {
            ExamenParticipation p = participation(1L, lot(10L));
            when(participationRepository.findById(1L)).thenReturn(Optional.of(p));
            when(lotRepository.findById(20L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deplacerEtudiant(20L, 1L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Déjà dans le lot cible → no-op (aucun save ni recalcul)")
        void deplace_dejaDansLeLot() {
            Lot target = lot(20L);
            ExamenParticipation p = participation(1L, target); // déjà dans le lot 20

            when(participationRepository.findById(1L)).thenReturn(Optional.of(p));
            when(lotRepository.findById(20L)).thenReturn(Optional.of(target));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(exam("CONFIGURE", 3, 4));

            ParticipationDTO dto = service.deplacerEtudiant(20L, 1L);

            assertThat(dto.lotId()).isEqualTo(20L);
            verify(participationRepository, never()).save(any());
            verify(participationRepository, never()).findByLotId(any());
        }
    }

    @Nested
    @DisplayName("#147 — Jour d'un lot (multi-jour, CONFIGURE)")
    class Jour {

        private Lot lot(long id) {
            Lot l = new Lot();
            l.setId(id);
            l.setExamenId(EXAM_ID);
            l.setNumeroLot((int) id);
            l.setStatut(LotStatus.EN_ATTENTE);
            return l;
        }

        @Test
        @DisplayName("Fixe le jour d'un lot (CONFIGURE)")
        void fixeLeJour() {
            Lot l = lot(7L);
            when(lotRepository.findById(7L)).thenReturn(Optional.of(l));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(exam("CONFIGURE", 3, 4));

            var dto = service.changerJour(7L, LocalDate.of(2026, 6, 21));

            assertThat(dto.jour()).isEqualTo(LocalDate.of(2026, 6, 21));
            assertThat(l.getJour()).isEqualTo(LocalDate.of(2026, 6, 21));
            verify(lotRepository, atLeastOnce()).save(l);
        }

        @Test
        @DisplayName("jour null = effacement EXPLICITE → retour au jour unique de l'examen")
        void nullEfface() {
            Lot l = lot(7L);
            l.setJour(LocalDate.of(2026, 6, 21));
            when(lotRepository.findById(7L)).thenReturn(Optional.of(l));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(exam("CONFIGURE", 3, 4));

            var dto = service.changerJour(7L, null);

            assertThat(dto.jour()).isNull();
            assertThat(l.getJour()).isNull();
        }

        @Test
        @DisplayName("Hors CONFIGURE → BusinessException, rien n'est écrit")
        void rejetteHorsConfigure() {
            Lot l = lot(7L);
            when(lotRepository.findById(7L)).thenReturn(Optional.of(l));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(exam("EN_COURS", 3, 4));

            assertThatThrownBy(() -> service.changerJour(7L, LocalDate.of(2026, 6, 21)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("CONFIGURE");
            assertThat(l.getJour()).isNull();
            verify(lotRepository, never()).save(any());
        }

        @Test
        @DisplayName("Lot inconnu → ResourceNotFoundException (404)")
        void lotInconnu() {
            when(lotRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.changerJour(99L, null))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
