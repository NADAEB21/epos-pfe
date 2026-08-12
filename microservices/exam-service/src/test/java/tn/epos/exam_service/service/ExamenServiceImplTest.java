package tn.epos.exam_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import tn.epos.exam_service.dto.request.ExamenRequest;
import tn.epos.exam_service.dto.response.ExamenResponse;
import tn.epos.exam_service.entities.Examen;
import tn.epos.exam_service.entities.GrilleEvaluation;
import tn.epos.exam_service.entities.ItemEvaluation;
import tn.epos.exam_service.entities.Station;
import tn.epos.exam_service.enums.StatutExamen;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.exam_service.config.MatiereAccessChecker;
import tn.epos.exam_service.dto.response.ExamTimingResponse;
import tn.epos.exam_service.repositories.ExamenRepository;
import org.springframework.security.access.AccessDeniedException;
import tn.epos.exam_service.services.impl.ExamenServiceImpl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import static org.mockito.ArgumentMatchers.eq;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExamenService - Tests unitaires")
class ExamenServiceImplTest {

    @Mock
    private ExamenRepository examenRepository;

    @Mock
    private MatiereAccessChecker matiereAccessChecker;

    /** #306 — qui lance. Le mock rend null par defaut : lance_par reste nul, pas d'echec. */
    @Mock
    private tn.epos.exam_service.config.CallerIdentity callerIdentity;

    @InjectMocks
    private ExamenServiceImpl examenService;

    private Examen examenBrouillon;
    private ExamenRequest examenRequest;

    // Horloge fixe (UTC) pour des assertions déterministes sur pause/reprise.
    private static final Instant FIXED_NOW = Instant.parse("2024-06-15T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        // Injection du uploadDir (@Value) + de l'horloge fixe (champ final) via réflexion.
        try {
            var uploadField = ExamenServiceImpl.class.getDeclaredField("uploadDir");
            uploadField.setAccessible(true);
            uploadField.set(examenService, "uploads/");

            var clockField = ExamenServiceImpl.class.getDeclaredField("clock");
            clockField.setAccessible(true);
            clockField.set(examenService, FIXED_CLOCK);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        examenRequest = new ExamenRequest();
        examenRequest.setNom("Examen Test");
        examenRequest.setMatiereId(1L);
        examenRequest.setDateExamen(LocalDate.of(2024, 6, 15));
        examenRequest.setDureeStationMin(15);
        examenRequest.setNbEtudiantsParStation(4);

        examenBrouillon = Examen.builder()
                .id(1L)
                .nom("Examen Test")
                .matiereId(1L)
                .dateExamen(LocalDate.of(2024, 6, 15))
                .dureeStationMin(15)
                .nbEtudiantsParStation(4)
                .statut(StatutExamen.BROUILLON)
                .build();
    }

    // CREER

    @Nested
    @DisplayName("creer()")
    class Creer {

        @Test
        @DisplayName("Doit créer un examen avec statut BROUILLON")
        void creer_devraitCreerExamen() {
            when(examenRepository.save(any(Examen.class))).thenReturn(examenBrouillon);

            ExamenResponse result = examenService.creer(examenRequest);

            assertThat(result).isNotNull();
            assertThat(result.getNom()).isEqualTo("Examen Test");
            assertThat(result.getStatut()).isEqualTo(StatutExamen.BROUILLON);
            verify(examenRepository, times(1)).save(any(Examen.class));
        }

        @Test
        @DisplayName("Doit appeler save() exactement une fois")
        void creer_devraitAppelerSaveUneFois() {
            when(examenRepository.save(any(Examen.class))).thenReturn(examenBrouillon);

            examenService.creer(examenRequest);

            verify(examenRepository, times(1)).save(any(Examen.class));
        }

        @Test
        @DisplayName("ADR-0012 : tempsBattementMin et avertissementLeadSec sont copiés vers l'entité")
        void creer_devraitCopierBattementEtAvertissement() {
            examenRequest.setTempsBattementMin(5);
            examenRequest.setAvertissementLeadSec(90);
            when(examenRepository.save(any(Examen.class))).thenAnswer(inv -> inv.getArgument(0));

            ArgumentCaptor<Examen> captor = ArgumentCaptor.forClass(Examen.class);
            ExamenResponse result = examenService.creer(examenRequest);

            verify(examenRepository).save(captor.capture());
            assertThat(captor.getValue().getTempsBattementMin()).isEqualTo(5);
            assertThat(captor.getValue().getAvertissementLeadSec()).isEqualTo(90);
            // Et toResponse les re-surface (contrat exposé au front + scoring).
            assertThat(result.getTempsBattementMin()).isEqualTo(5);
            assertThat(result.getAvertissementLeadSec()).isEqualTo(90);
        }
    }

    // LISTER

    @Nested
    @DisplayName("listerTous()")
    class ListerTous {

        @Test
        @DisplayName("SUPER_ADMIN: doit retourner la liste de tous les examens")
        void listerTous_superAdmin_devraitRetournerListe() {
            Page<Examen> pageEntite = new PageImpl<>(List.of(examenBrouillon));
            when(matiereAccessChecker.isUnrestricted()).thenReturn(true);
            when(examenRepository.findAll(any(Pageable.class))).thenReturn(pageEntite);

            Page<ExamenResponse> result = examenService.listerTous(Pageable.unpaged());

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getNom()).isEqualTo("Examen Test");
        }

        @Test
        @DisplayName("SUPER_ADMIN: doit retourner liste vide si aucun examen")
        void listerTous_superAdmin_devraitRetournerListeVide() {
            Page<Examen> pageVide = new PageImpl<>(List.of());
            when(matiereAccessChecker.isUnrestricted()).thenReturn(true);
            when(examenRepository.findAll(any(Pageable.class))).thenReturn(pageVide);

            Page<ExamenResponse> result = examenService.listerTous(Pageable.unpaged());

            assertThat(result.getContent()).isEmpty();
        }

        @Test
        @DisplayName("SUPER_ADMIN: doit filtrer par statut sans filtre matière")
        void listerParStatut_superAdmin_devraitFiltrerParStatut() {
            Page<Examen> page = new PageImpl<>(List.of(examenBrouillon));
            when(matiereAccessChecker.isUnrestricted()).thenReturn(true);
            when(examenRepository.findByStatut(eq(StatutExamen.BROUILLON), any(Pageable.class)))
                    .thenReturn(page);

            Page<ExamenResponse> result = examenService.listerParStatut(
                    StatutExamen.BROUILLON, Pageable.unpaged());

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getStatut()).isEqualTo(StatutExamen.BROUILLON);
        }

        // ── #95 — RESPONSABLE_MATIERE scope filter ──────────────────────────

        @Test
        @DisplayName("#95 — RESPONSABLE: doit appeler findByMatiereIdIn avec son périmètre")
        void listerTous_responsable_devraitFiltrerSurSonPerimetre() {
            Page<Examen> page = new PageImpl<>(List.of(examenBrouillon));
            when(matiereAccessChecker.isUnrestricted()).thenReturn(false);
            when(matiereAccessChecker.getAccessibleMatiereIds()).thenReturn(Set.of(1L, 7L));
            when(examenRepository.findByMatiereIdIn(eq(Set.of(1L, 7L)), any(Pageable.class)))
                    .thenReturn(page);

            Page<ExamenResponse> result = examenService.listerTous(Pageable.unpaged());

            assertThat(result.getContent()).hasSize(1);
            verify(examenRepository, never()).findAll(any(Pageable.class));
        }

        @Test
        @DisplayName("#95 — RESPONSABLE sans périmètre: doit retourner page vide sans hit DB")
        void listerTous_responsableSansPerimetre_devraitRetournerVide() {
            when(matiereAccessChecker.isUnrestricted()).thenReturn(false);
            when(matiereAccessChecker.getAccessibleMatiereIds()).thenReturn(Set.of());

            Page<ExamenResponse> result = examenService.listerTous(Pageable.unpaged());

            assertThat(result.getContent()).isEmpty();
            verify(examenRepository, never()).findAll(any(Pageable.class));
            verify(examenRepository, never()).findByMatiereIdIn(any(), any(Pageable.class));
        }

        @Test
        @DisplayName("#95 — RESPONSABLE: listerParStatut doit appeler findByMatiereIdInAndStatut")
        void listerParStatut_responsable_devraitFiltrerScopePlusStatut() {
            Page<Examen> page = new PageImpl<>(List.of(examenBrouillon));
            when(matiereAccessChecker.isUnrestricted()).thenReturn(false);
            when(matiereAccessChecker.getAccessibleMatiereIds()).thenReturn(Set.of(1L));
            when(examenRepository.findByMatiereIdInAndStatut(
                    eq(Set.of(1L)), eq(StatutExamen.BROUILLON), any(Pageable.class)))
                    .thenReturn(page);

            Page<ExamenResponse> result = examenService.listerParStatut(
                    StatutExamen.BROUILLON, Pageable.unpaged());

            assertThat(result.getContent()).hasSize(1);
            verify(examenRepository, never()).findByStatut(any(), any(Pageable.class));
        }

        @Test
        @DisplayName("#95 — RESPONSABLE sans périmètre: listerParStatut doit retourner vide sans hit DB")
        void listerParStatut_responsableSansPerimetre_devraitRetournerVide() {
            when(matiereAccessChecker.isUnrestricted()).thenReturn(false);
            when(matiereAccessChecker.getAccessibleMatiereIds()).thenReturn(Set.of());

            Page<ExamenResponse> result = examenService.listerParStatut(
                    StatutExamen.BROUILLON, Pageable.unpaged());

            assertThat(result.getContent()).isEmpty();
            verify(examenRepository, never()).findByStatut(any(), any(Pageable.class));
            verify(examenRepository, never()).findByMatiereIdInAndStatut(any(), any(), any(Pageable.class));
        }
    }

    // TROUVER PAR ID

    @Nested
    @DisplayName("trouverParId()")
    class TrouverParId {

        @Test
        @DisplayName("Doit retourner l'examen si trouvé")
        void trouverParId_devraitRetournerExamen() {
            when(examenRepository.findByIdWithStations(1L))
                    .thenReturn(Optional.of(examenBrouillon));

            ExamenResponse result = examenService.trouverParId(1L);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si introuvable")
        void trouverParId_devraitLeverExceptionSiIntrouvable() {
            when(examenRepository.findByIdWithStations(99L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> examenService.trouverParId(99L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("99");
        }

        @Test
        @DisplayName("Les stations embarquées doivent porter leurs evaluateurIds")
        void trouverParId_stationsEmbarquees_devraientPorterEvaluateurIds() {
            Station station = Station.builder()
                    .id(10L)
                    .nom("Station 1")
                    .ordre(1)
                    .examen(examenBrouillon)
                    .evaluateurIds(List.of(3L, 7L))
                    .build();
            examenBrouillon.setStations(List.of(station));
            when(examenRepository.findByIdWithStations(1L))
                    .thenReturn(Optional.of(examenBrouillon));

            ExamenResponse result = examenService.trouverParId(1L);

            assertThat(result.getStations()).hasSize(1);
            assertThat(result.getStations().get(0).getEvaluateurIds())
                    .containsExactly(3L, 7L);
        }
    }

    // MODIFIER

    @Nested
    @DisplayName("modifier()")
    class Modifier {

        @Test
        @DisplayName("Doit modifier l'examen si statut BROUILLON")
        void modifier_devraitModifierSiStatutBrouillon() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));
            when(examenRepository.save(any())).thenReturn(examenBrouillon);

            examenRequest.setNom("Nouveau nom");
            ExamenResponse result = examenService.modifier(1L, examenRequest);

            assertThat(result).isNotNull();
            verify(examenRepository).save(any(Examen.class));
        }

        @Test
        @DisplayName("Doit lever BusinessException si examen non en BROUILLON")
        void modifier_devraitLeverExceptionSiNonBrouillon() {
            examenBrouillon.setStatut(StatutExamen.EN_COURS);
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));

            assertThatThrownBy(() -> examenService.modifier(1L, examenRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("BROUILLON");
        }
    }

    // CHANGER STATUT

    @Nested
    @DisplayName("changerStatut()")
    class ChangerStatut {

        @Test
        @DisplayName("Transition BROUILLON → CONFIGURE doit réussir")
        void changerStatut_brouillonVersConfigure_doitReussir() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));
            when(examenRepository.save(any())).thenReturn(examenBrouillon);

            ExamenResponse result = examenService.changerStatut(1L, StatutExamen.CONFIGURE);

            assertThat(result).isNotNull();
            verify(examenRepository).save(any());
        }

        /**
         * #306 / ADR-0024 — le lancement enregistre son AUTEUR, à côté de son instant.
         *
         * <p>`launched_at` (ADR-0010) disait QUAND l'acte le plus lourd du produit avait eu lieu ;
         * rien ne disait PAR QUI. C'est le pendant de `lot.ouvert_par` côté scoring — le lanceur
         * ne désigne le conducteur que par défaut, tant qu'aucune vague n'a été ouverte.
         */
        @Test
        @DisplayName("#306 — le passage à EN_COURS enregistre QUI a lancé")
        void changerStatut_enCours_enregistreLeLanceur() {
            examenBrouillon.setStatut(StatutExamen.CONFIGURE);
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));
            when(examenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(callerIdentity.getCallerUserId()).thenReturn(9L);

            examenService.changerStatut(1L, StatutExamen.EN_COURS);

            assertThat(examenBrouillon.getLancePar()).isEqualTo(9L);
            assertThat(examenBrouillon.getLaunchedAt())
                    .as("les deux moitiés du même fait")
                    .isNotNull();
        }

        /**
         * Une identité absente ne doit PAS refuser le lancement : c'est une trace, pas une garde.
         * Le droit d'agir a déjà été tranché par checkAccess (#274).
         */
        @Test
        @DisplayName("#306 — lanceur non identifiable : l'examen se lance, lance_par reste null")
        void changerStatut_enCours_sansAuteur_lanceQuandMeme() {
            examenBrouillon.setStatut(StatutExamen.CONFIGURE);
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));
            when(examenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(callerIdentity.getCallerUserId()).thenReturn(null);

            examenService.changerStatut(1L, StatutExamen.EN_COURS);

            assertThat(examenBrouillon.getStatut()).isEqualTo(StatutExamen.EN_COURS);
            assertThat(examenBrouillon.getLancePar()).isNull();
        }

        @Test
        @DisplayName("Transition BROUILLON → EN_COURS doit lever BusinessException")
        void changerStatut_brouillonVersEnCours_doitEchouer() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));

            assertThatThrownBy(() -> examenService.changerStatut(1L, StatutExamen.EN_COURS))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("invalide");
        }

        @Test
        @DisplayName("Transition ARCHIVE → tout doit lever BusinessException")
        void changerStatut_archive_doitToujoursEchouer() {
            examenBrouillon.setStatut(StatutExamen.ARCHIVE);
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));

            assertThatThrownBy(() -> examenService.changerStatut(1L, StatutExamen.BROUILLON))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("ADR-0010 : CONFIGURE → EN_COURS pose launched_at = horloge (instant de lancement)")
        void changerStatut_versEnCours_doitPoserLaunchedAt() {
            Examen configure = Examen.builder()
                    .id(1L).nom("Examen Test").matiereId(1L)
                    .dateExamen(LocalDate.of(2024, 6, 15))
                    .statut(StatutExamen.CONFIGURE)
                    .build();
            when(examenRepository.findById(1L)).thenReturn(Optional.of(configure));
            when(examenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ExamenResponse result = examenService.changerStatut(1L, StatutExamen.EN_COURS);

            assertThat(result.getStatut()).isEqualTo(StatutExamen.EN_COURS);
            // Posé depuis l'horloge fixe injectée (déterministe).
            assertThat(result.getLaunchedAt())
                    .isEqualTo(LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));
        }

        @Test
        @DisplayName("ADR-0010 : les transitions hors EN_COURS ne posent pas launched_at")
        void changerStatut_horsEnCours_neToucheePasLaunchedAt() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));
            when(examenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ExamenResponse result = examenService.changerStatut(1L, StatutExamen.CONFIGURE);

            assertThat(result.getLaunchedAt()).isNull();
        }

        @Test
        @DisplayName("EN_COURS → TERMINE réussit quand l'examen n'est pas en pause")
        void changerStatut_versTermine_doitReussirSiPasEnPause() {
            Examen enCours = Examen.builder()
                    .id(1L).nom("Examen Test").matiereId(1L)
                    .dateExamen(LocalDate.of(2024, 6, 15))
                    .statut(StatutExamen.EN_COURS).enPause(false)
                    .build();
            when(examenRepository.findById(1L)).thenReturn(Optional.of(enCours));
            when(examenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ExamenResponse result = examenService.changerStatut(1L, StatutExamen.TERMINE);

            assertThat(result.getStatut()).isEqualTo(StatutExamen.TERMINE);
        }

        @Test
        @DisplayName("EN_COURS → TERMINE doit échouer si l'examen est en pause (reprendre d'abord)")
        void changerStatut_versTermineEnPause_doitEchouer() {
            Examen enPause = Examen.builder()
                    .id(1L).nom("Examen Test").matiereId(1L)
                    .dateExamen(LocalDate.of(2024, 6, 15))
                    .statut(StatutExamen.EN_COURS).enPause(true)
                    .build();
            when(examenRepository.findById(1L)).thenReturn(Optional.of(enPause));

            assertThatThrownBy(() -> examenService.changerStatut(1L, StatutExamen.TERMINE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Reprenez");
            verify(examenRepository, never()).save(any());
        }
    }

    // #265 — CONFLITS D'ÉVALUATEURS INTER-EXAMENS

    @Nested
    @DisplayName("#265 — évaluateurs partagés avec un autre examen EN_COURS")
    class ConflitsEvaluateurs {

        private Examen examAvecEvals(long id, String nom, StatutExamen statut, List<Long>... evalsParStation) {
            Examen e = Examen.builder()
                    .id(id).nom(nom).matiereId(1L)
                    .dateExamen(LocalDate.of(2024, 6, 15))
                    .statut(statut)
                    .build();
            int ordre = 1;
            for (List<Long> evals : evalsParStation) {
                e.getStations().add(Station.builder()
                        .id(id * 100 + ordre).nom("St" + ordre).ordre(ordre++)
                        .evaluateurIds(new java.util.ArrayList<>(evals))
                        .build());
            }
            return e;
        }

        /**
         * #276 — un barème qui ne permet pas d'atteindre la note annoncée plafonne
         * TOUTE la promotion, et ADR-0015 le fige au lancement : immuable ensuite.
         * Reproduit en direct avant correctif (station notée sur 20, un critère de
         * 10 pts → un sans-faute obtenait 10/20, lancement accepté en 200).
         */
        @Test
        @DisplayName("#276 Lancement REFUSÉ : le barème ne permet pas d'atteindre noteMax")
        void lancement_refuse_siBaremeInatteignable() {
            Examen configure = examenAvecStation(baremeDe(20.0, 10.0));
            when(examenRepository.findById(1L)).thenReturn(Optional.of(configure));

            assertThatThrownBy(() -> examenService.changerStatut(1L, StatutExamen.EN_COURS))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Station piege")
                    .hasMessageContaining("10");

            verify(examenRepository, never()).save(any());
        }

        /**
         * Le second chemin, plus sournois : les BUDGETS somment bien à noteMax
         * (ponderationValide = true) mais les critères sont notés sur moins.
         * Un garde qui ne regarderait que la somme des pondérations le laisserait passer.
         */
        @Test
        @DisplayName("#276 Lancement REFUSÉ : budgets corrects mais valeurMax insuffisantes")
        void lancement_refuse_siBudgetsValidesMaisInatteignables() {
            GrilleEvaluation grille = baremeDe(20.0, 10.0, 10.0);   // budgets 10+10 = 20 ✓
            grille.getItems().forEach(i -> i.setValeurMax(5.0));    // mais notés sur 5+5 = 10
            Examen configure = examenAvecStation(grille);
            when(examenRepository.findById(1L)).thenReturn(Optional.of(configure));

            // Le barème est « valide » au sens des budgets…
            assertThat(grille.isPonderationValide()).isTrue();
            // …et pourtant inatteignable.
            assertThat(grille.getMaxAtteignable()).isEqualTo(10.0);

            assertThatThrownBy(() -> examenService.changerStatut(1L, StatutExamen.EN_COURS))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("#276 Lancement ACCEPTÉ quand un sans-faute peut atteindre noteMax")
        void lancement_accepte_siBaremeAtteignable() {
            Examen configure = examenAvecStation(baremeDe(20.0, 12.0, 8.0));
            when(examenRepository.findById(1L)).thenReturn(Optional.of(configure));
            when(examenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ExamenResponse r = examenService.changerStatut(1L, StatutExamen.EN_COURS);

            assertThat(r.getStatut()).isEqualTo(StatutExamen.EN_COURS);
        }

        /** Grille dont chaque critère est NUMERIQUE, noté sur toute sa pondération. */
        private GrilleEvaluation baremeDe(double noteMax, double... ponderations) {
            GrilleEvaluation g = new GrilleEvaluation();
            g.setId(9L);
            g.setNoteMax(noteMax);
            for (double p : ponderations) {
                ItemEvaluation it = new ItemEvaluation();
                it.setType(tn.epos.exam_service.enums.TypeItem.NUMERIQUE);
                it.setPonderation(p);
                it.setValeurMax(p);
                it.setGrille(g);
                g.getItems().add(it);
            }
            return g;
        }

        private Examen examenAvecStation(GrilleEvaluation grille) {
            Station st = new Station();
            st.setId(5L);
            st.setNom("Station piege");
            st.setGrille(grille);
            Examen e = Examen.builder()
                    .id(1L).nom("Examen Test").matiereId(1L)
                    .dateExamen(LocalDate.of(2024, 6, 15))
                    .statut(StatutExamen.CONFIGURE)
                    .build();
            e.setStations(new java.util.ArrayList<>(java.util.List.of(st)));
            return e;
        }

        @Test
        @DisplayName("Lancement REFUSÉ : un autre examen EN_COURS partage un évaluateur")
        void lancement_refuse_siEvaluateurPartage() {
            Examen aLancer = examAvecEvals(1L, "Examen B", StatutExamen.CONFIGURE, List.of(3L), List.of(6L));
            Examen enCours = examAvecEvals(2L, "Examen A", StatutExamen.EN_COURS, List.of(3L), List.of(9L));
            when(examenRepository.findById(1L)).thenReturn(Optional.of(aLancer));
            when(examenRepository.findAllByStatut(StatutExamen.EN_COURS)).thenReturn(List.of(enCours));

            assertThatThrownBy(() -> examenService.changerStatut(1L, StatutExamen.EN_COURS))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Examen A")
                    .hasMessageContaining("déjà engagés");
            verify(examenRepository, never()).save(any());
        }

        @Test
        @DisplayName("Lancement PERMIS : examens simultanés à évaluateurs disjoints")
        void lancement_permis_siEvaluateursDisjoints() {
            Examen aLancer = examAvecEvals(1L, "Examen B", StatutExamen.CONFIGURE, List.of(3L));
            Examen enCours = examAvecEvals(2L, "Examen A", StatutExamen.EN_COURS, List.of(9L));
            when(examenRepository.findById(1L)).thenReturn(Optional.of(aLancer));
            when(examenRepository.findAllByStatut(StatutExamen.EN_COURS)).thenReturn(List.of(enCours));
            when(examenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ExamenResponse result = examenService.changerStatut(1L, StatutExamen.EN_COURS);

            assertThat(result.getStatut()).isEqualTo(StatutExamen.EN_COURS);
        }

        @Test
        @DisplayName("Sans évaluateur affecté, aucune requête inter-examens n'est faite")
        void lancement_sansEvaluateurs_neScannePas() {
            Examen aLancer = examAvecEvals(1L, "Examen B", StatutExamen.CONFIGURE);
            when(examenRepository.findById(1L)).thenReturn(Optional.of(aLancer));
            when(examenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            examenService.changerStatut(1L, StatutExamen.EN_COURS);

            verify(examenRepository, never()).findAllByStatut(any());
        }

        @Test
        @DisplayName("listerConflitsEvaluateurs : renvoie les évaluateurs partagés, nommés par examen")
        void listerConflits_renvoieLesPartages() {
            Examen mien = examAvecEvals(1L, "Examen B", StatutExamen.CONFIGURE, List.of(3L, 6L));
            Examen autre = examAvecEvals(2L, "Examen A", StatutExamen.EN_COURS, List.of(6L), List.of(3L));
            Examen disjoint = examAvecEvals(4L, "Examen C", StatutExamen.EN_COURS, List.of(9L));
            when(examenRepository.findById(1L)).thenReturn(Optional.of(mien));
            when(examenRepository.findAllByStatut(StatutExamen.EN_COURS))
                    .thenReturn(List.of(autre, disjoint));

            var conflits = examenService.listerConflitsEvaluateurs(1L);

            assertThat(conflits).hasSize(1);
            assertThat(conflits.get(0).getExamenNom()).isEqualTo("Examen A");
            assertThat(conflits.get(0).getEvaluateurIds()).containsExactly(3L, 6L);
        }

        @Test
        @DisplayName("L'examen ne se voit jamais en conflit avec lui-même")
        void listerConflits_ignoreSoiMeme() {
            Examen mien = examAvecEvals(1L, "Examen B", StatutExamen.EN_COURS, List.of(3L));
            when(examenRepository.findById(1L)).thenReturn(Optional.of(mien));
            when(examenRepository.findAllByStatut(StatutExamen.EN_COURS)).thenReturn(List.of(mien));

            assertThat(examenService.listerConflitsEvaluateurs(1L)).isEmpty();
        }
    }

    // SUPPRIMER

    @Nested
    @DisplayName("supprimer()")
    class Supprimer {

        @Test
        @DisplayName("Doit supprimer si statut BROUILLON")
        void supprimer_devraitSupprimerSiBrouillon() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));

            examenService.supprimer(1L);

            verify(examenRepository).delete(examenBrouillon);
        }

        @Test
        @DisplayName("Doit lever BusinessException si examen EN_COURS")
        void supprimer_devraitLeverExceptionSiEnCours() {
            examenBrouillon.setStatut(StatutExamen.EN_COURS);
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));

            assertThatThrownBy(() -> examenService.supprimer(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("EN_COURS");
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si examen introuvable")
        void supprimer_devraitLeverExceptionSiIntrouvable() {
            when(examenRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> examenService.supprimer(99L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // PAUSE / REPRISE (ADR-0009)

    @Nested
    @DisplayName("mettreEnPause() / reprendre()")
    class PauseReprise {

        private Examen examenEnCours() {
            return Examen.builder()
                    .id(1L).nom("Examen Test").matiereId(1L)
                    .dateExamen(LocalDate.of(2024, 6, 15))
                    .statut(StatutExamen.EN_COURS)
                    .enPause(false).totalPauseSec(0)
                    .build();
        }

        @Test
        @DisplayName("Pause d'un examen EN_COURS : enPause=true, pausedAt = horloge, statut inchangé")
        void mettreEnPause_enCours_doitMettreEnPause() {
            Examen exam = examenEnCours();
            when(examenRepository.findById(1L)).thenReturn(Optional.of(exam));
            when(examenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ExamenResponse result = examenService.mettreEnPause(1L);

            assertThat(result.isEnPause()).isTrue();
            // pausedAt vient de l'horloge fixe injectée (déterministe).
            assertThat(result.getPausedAt())
                    .isEqualTo(LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));
            // Pause est orthogonale : le statut reste EN_COURS.
            assertThat(result.getStatut()).isEqualTo(StatutExamen.EN_COURS);
        }

        @Test
        @DisplayName("Pause d'un examen non EN_COURS doit lever BusinessException")
        void mettreEnPause_nonEnCours_doitEchouer() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));

            assertThatThrownBy(() -> examenService.mettreEnPause(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("EN_COURS");
        }

        @Test
        @DisplayName("Pause d'un examen déjà en pause doit lever BusinessException")
        void mettreEnPause_dejaEnPause_doitEchouer() {
            Examen exam = examenEnCours();
            exam.setEnPause(true);
            exam.setPausedAt(LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));
            when(examenRepository.findById(1L)).thenReturn(Optional.of(exam));

            assertThatThrownBy(() -> examenService.mettreEnPause(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("déjà");
        }

        @Test
        @DisplayName("Reprise cumule la durée écoulée et efface pausedAt")
        void reprendre_doitCumulerEtEffacer() {
            Examen exam = examenEnCours();
            exam.setEnPause(true);
            // 5s avant l'instant fixe de l'horloge → durée de pause = 5s, exactement.
            exam.setPausedAt(LocalDateTime.ofInstant(FIXED_NOW.minusSeconds(5), ZoneOffset.UTC));
            exam.setTotalPauseSec(10);
            when(examenRepository.findById(1L)).thenReturn(Optional.of(exam));
            when(examenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ExamenResponse result = examenService.reprendre(1L);

            assertThat(result.isEnPause()).isFalse();
            assertThat(result.getPausedAt()).isNull();
            // 10s cumulés + 5s de cette pause = 15 (déterministe).
            assertThat(result.getTotalPauseSec()).isEqualTo(15);
        }

        @Test
        @DisplayName("Reprise d'un examen non en pause doit lever BusinessException")
        void reprendre_nonEnPause_doitEchouer() {
            Examen exam = examenEnCours();
            when(examenRepository.findById(1L)).thenReturn(Optional.of(exam));

            assertThatThrownBy(() -> examenService.reprendre(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("pas en pause");
        }
    }

    // IMPORT PDF

    @Nested
    @DisplayName("importerPdf()")
    class ImporterPdf {

        @Test
        @DisplayName("Doit lever BusinessException si fichier vide")
        void importerPdf_devraitLeverExceptionSiFichierVide() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));
            MockMultipartFile fichierVide = new MockMultipartFile(
                    "fichier", "test.pdf", "application/pdf", new byte[0]);

            assertThatThrownBy(() -> examenService.importerPdf(1L, fichierVide))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("vide");
        }

        @Test
        @DisplayName("Doit lever BusinessException si fichier non PDF")
        void importerPdf_devraitLeverExceptionSiNonPdf() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));
            MockMultipartFile fichierWord = new MockMultipartFile(
                    "fichier", "test.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "contenu".getBytes());

            assertThatThrownBy(() -> examenService.importerPdf(1L, fichierWord))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("PDF");
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si PDF demandé mais absent")
        void obtenirCheminPdf_devraitLeverExceptionSiAbsent() {
            examenBrouillon.setPdfSujetPath(null);
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));

            assertThatThrownBy(() -> examenService.obtenirCheminPdf(1L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    /**
     * getTiming() — la lecture d'état ouverte à l'ÉVALUATEUR.
     *
     * <p>Raison d'être : {@code trouverParId()} passe par {@code checkAccess} (droit d'écriture)
     * et renvoie donc 403 à un évaluateur. scoring-service appelait pourtant cet endpoint pour
     * connaître le statut de l'examen : le 403 était avalé dans un repli « neutre »
     * (statut = null) et le dashboard évaluateur se vidait ENTIÈREMENT le jour J.
     *
     * <p>Ce que ces tests verrouillent : getTiming() utilise {@code checkReadAccess} (qui
     * autorise l'évaluateur, comme pour la lecture de grille), expose l'état d'exécution — et
     * RIEN de plus — tout en refusant toujours un appelant hors périmètre.
     */
    @Nested
    @DisplayName("getTiming() — état d'exécution lisible par l'évaluateur")
    class GetTiming {

        private Examen examenEnCoursPause() {
            return Examen.builder()
                    .id(1L).nom("Examen Test").matiereId(1L)
                    .dateExamen(LocalDate.of(2024, 6, 15))
                    .statut(StatutExamen.EN_COURS)
                    .dureeStationMin(15)
                    .avertissementLeadSec(60)
                    .enPause(true)
                    .totalPauseSec(120)
                    .build();
        }

        @Test
        @DisplayName("Retourne le statut et l'état de pause de l'examen")
        void getTiming_exposeLEtatDExecution() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenEnCoursPause()));

            ExamTimingResponse timing = examenService.getTiming(1L);

            assertThat(timing.getId()).isEqualTo(1L);
            assertThat(timing.getStatut()).isEqualTo(StatutExamen.EN_COURS);
            assertThat(timing.isEnPause()).isTrue();
            assertThat(timing.getTotalPauseSec()).isEqualTo(120);
            assertThat(timing.getDureeStationMin()).isEqualTo(15);
            assertThat(timing.getAvertissementLeadSec()).isEqualTo(60);
        }

        @Test
        @DisplayName("Utilise checkReadAccess (qui autorise l'ÉVALUATEUR) et NON checkAccess")
        void getTiming_utiliseLeControleDeLecture() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenEnCoursPause()));

            examenService.getTiming(1L);

            // Le cœur du correctif : un évaluateur doit passer. checkAccess() le refuserait
            // (403) et scoring avalerait l'erreur → dashboard évaluateur vide.
            verify(matiereAccessChecker).checkReadAccess(1L);
            verify(matiereAccessChecker, never()).checkAccess(anyLong());
        }

        @Test
        @DisplayName("Appelant hors périmètre → AccessDeniedException (la portée matière tient toujours)")
        void getTiming_horsPerimetre_devraitRefuser() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenEnCoursPause()));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkReadAccess(1L);

            assertThatThrownBy(() -> examenService.getTiming(1L))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Examen inexistant → ResourceNotFoundException")
        void getTiming_introuvable() {
            when(examenRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> examenService.getTiming(99L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // RÉINITIALISER (#183 — « dé-lancer » EN_COURS → CONFIGURE)

    @Nested
    @DisplayName("reinitialiser() — retour EN_COURS → CONFIGURE")
    class Reinitialiser {

        private Examen examenLance() {
            return Examen.builder()
                    .id(1L).nom("Examen Test").matiereId(1L)
                    .dateExamen(LocalDate.of(2024, 6, 15))
                    .statut(StatutExamen.EN_COURS)
                    .launchedAt(LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC))
                    .enPause(true)
                    .pausedAt(LocalDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC))
                    .totalPauseSec(120)
                    .build();
        }

        @Test
        @DisplayName("Depuis EN_COURS : repasse CONFIGURE et efface launched_at + tout l'état de pause")
        void reinitialiser_depuisEnCours_doitEffacerLancementEtPause() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenLance()));
            when(examenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ExamenResponse result = examenService.reinitialiser(1L);

            assertThat(result.getStatut()).isEqualTo(StatutExamen.CONFIGURE);
            assertThat(result.getLaunchedAt()).isNull();
            assertThat(result.getPausedAt()).isNull();
            assertThat(result.isEnPause()).isFalse();
            assertThat(result.getTotalPauseSec()).isZero();
        }

        @Test
        @DisplayName("Vérifie le périmètre matière avant d'écrire (checkAccess)")
        void reinitialiser_doitVerifierLePerimetre() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenLance()));
            when(examenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            examenService.reinitialiser(1L);

            verify(matiereAccessChecker).checkAccess(1L);
        }

        @Test
        @DisplayName("Appelant hors périmètre → AccessDeniedException, aucune écriture")
        void reinitialiser_horsPerimetre_doitRefuser() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenLance()));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            assertThatThrownBy(() -> examenService.reinitialiser(1L))
                    .isInstanceOf(AccessDeniedException.class);
            verify(examenRepository, never()).save(any());
        }

        @Test
        @DisplayName("Depuis un statut ≠ EN_COURS (CONFIGURE) → BusinessException, aucune écriture")
        void reinitialiser_nonEnCours_doitEchouer() {
            examenBrouillon.setStatut(StatutExamen.CONFIGURE);
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));

            assertThatThrownBy(() -> examenService.reinitialiser(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("EN_COURS");
            verify(examenRepository, never()).save(any());
        }

        @Test
        @DisplayName("Examen introuvable → ResourceNotFoundException")
        void reinitialiser_introuvable_doitLeverNotFound() {
            when(examenRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> examenService.reinitialiser(99L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
