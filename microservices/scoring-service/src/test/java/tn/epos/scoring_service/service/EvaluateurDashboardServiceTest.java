package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.dto.dashboard.*;
import tn.epos.scoring_service.entities.*;
import tn.epos.scoring_service.repositories.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EvaluateurDashboardService}.
 *
 * Couvre chaque méthode publique et les branches non triviales :
 *   buildDashboard / resolveSessionStatut / buildSessions / buildPlanning /
 *   getGroupeDetail / getGroupeSuivant / saisirNotation / validerEtudiant /
 *   validerGroupe / validerLot.
 *
 * getGroupeDetail/getGroupeSuivant/validerGroupe remplacent l'ancien
 * getLotDetail(stationId, lotNumero, evaluateurId), ambigu dès qu'un
 * évaluateur reçoit plusieurs rotations pour un même lot (une par groupe qui
 * passe à sa station) : (stationId, lotNumero) ne désignait pas un groupe
 * précis, findFirst() renvoyait toujours le même.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EvaluateurDashboardService — tests complets")
class EvaluateurDashboardServiceTest {

    @Mock private ILotRepository                 lotRepository;
    @Mock private IRotationRepository            rotationRepository;
    @Mock private IRotationAssignmentRepository  rotationAssignmentRepository;
    @Mock private INotationRepository            notationRepository;
    @Mock private INotationItemRepository        notationItemRepository;
    @Mock private IExamenParticipationRepository participationRepository;
    @Mock private IStudentGroupRepository        studentGroupRepository;
    @Mock private ExamServiceClient              examServiceClient;
    @Mock private ExamDefinitionSnapshotService  examDefinitionSnapshot;
    @Mock private SimpMessagingTemplate          messagingTemplate;

    // Vraie horloge (pas un mock) pinnée Africa/Tunis comme ClockConfig, pour que
    // le temps effectif du service s'aligne sur les debutCreneau construits ici.
    @Spy private Clock clock = Clock.system(ZoneId.of("Africa/Tunis"));

    /** #274 — permissif ici : le perimetre de matiere a ses propres tests. */
    @Mock private MatiereAccessGuard matiereAccessGuard;

    @InjectMocks
    private EvaluateurDashboardService service;

    private static final Long EVAL_ID    = 1L;
    private static final Long STATION_ID = 100L;
    private static final ZoneId TUNIS    = ZoneId.of("Africa/Tunis");

    @BeforeEach
    void globalSetup() {
        lenient().when(rotationRepository.findDistinctExamenIdsByEvaluateurId(EVAL_ID))
                .thenReturn(List.of(99L));
        lenient().when(examServiceClient.getExamTiming(anyLong()))
                .thenReturn(new ExamServiceClient.ExamTiming(false, null, 0, 15, 0, "EN_COURS"));
        lenient().when(examServiceClient.getStationInfo(anyLong()))
                .thenReturn(new ExamServiceClient.StationInfo("Station Test"));
        lenient().when(examDefinitionSnapshot.resolveStationNom(any(), anyLong()))
                .thenReturn("Station Test");
        // weigh() garde l'arithmétique RÉELLE (déléguée à ExamItemSnapshot) : si on la
        // stubbait, les tests de score ne vérifieraient plus que le mock sait compter.
        lenient().when(examDefinitionSnapshot.weigh(anyMap(), anyLong(), any()))
                .thenAnswer(inv -> {
                    Map<Long, ExamItemSnapshot> def = inv.getArgument(0);
                    ExamItemSnapshot item = def.get((Long) inv.getArgument(1));
                    if (item == null) {
                        throw new BusinessException("Item hors snapshot (ADR-0015)");
                    }
                    return item.weigh(inv.getArgument(2));
                });
        // #213 — le garde d'écriture exige que l'évaluateur tienne la station.
        // EVAL_ID tient STATION_ID dans toute cette classe : c'est le cas NORMAL,
        // et sans ce stub chaque test de notation échouerait sur le garde au lieu
        // de tester ce qu'il prétend tester. Les tests du garde lui-même stubent
        // explicitement l'absence de rotation.
        lenient().when(rotationRepository.existsByEvaluateurIdAndStationId(EVAL_ID, STATION_ID))
                .thenReturn(true);
    }

    // ─── shared helpers ──────────────────────────────────────────────────────

    /**
     * ADR-0015 — définition figée d'une grille à un seul critère notable. Remplace les stubs
     * {@code getItemInfosForGrille(...) → Map.of()} : une map vide ne signifie plus « on ne sait
     * pas, laisse passer » mais « aucun critère notable », ce qui doit désormais échouer.
     */
    private Map<Long, ExamItemSnapshot> definition(long itemId, double ponderation, String type) {
        return Map.of(itemId, ExamItemSnapshot.builder()
                .examenId(99L).grilleId(1L).itemId(itemId)
                .ponderation(ponderation).type(type)
                .build());
    }

    private Rotation rotationAt(LocalDateTime debut) {
        Rotation r = new Rotation();
        r.setId(1L);
        r.setStationId(STATION_ID);
        r.setEvaluateurId(EVAL_ID);
        r.setDebutCreneau(debut);
        r.setStatut(RotationStatus.EN_ATTENTE);
        return r;
    }

    private Lot lotFor(Rotation r, LotStatus status) {
        Lot lot = new Lot();
        lot.setId(10L);
        lot.setExamenId(99L);
        lot.setEvaluateurId(EVAL_ID);
        lot.setNumeroLot(1);
        lot.setTailleLot(6);
        lot.setStatut(status);

        StudentGroup sg = new StudentGroup();
        sg.setId(1L);
        sg.setNumeroGroupe(1);
        sg.setLot(lot);
        sg.setRotations(List.of(r));
        lot.setGroups(List.of(sg));
        r.setStudentGroup(sg);
        return lot;
    }

    /**
     * Relie une rotation minimale (avec évaluateur + numéro de groupe) à un lot
     * donné, via un StudentGroup — reproduit ce que lit
     * resolverLotsDepuisRotations() / toGroupeDetailResponse() côté service.
     */
    private Rotation rotationWithLot(Long rotationId, Lot lot, int numeroGroupe) {
        Rotation r = new Rotation();
        r.setId(rotationId);
        r.setStationId(STATION_ID);
        r.setEvaluateurId(EVAL_ID);
        r.setStatut(RotationStatus.EN_ATTENTE);
        StudentGroup sg = new StudentGroup();
        sg.setNumeroGroupe(numeroGroupe);
        sg.setLot(lot);
        sg.setRotations(List.of(r));
        r.setStudentGroup(sg);
        return r;
    }

    private ExamenParticipation participation(Long id) {
        Etudiant e = new Etudiant();
        e.setId(id);
        e.setNom("Nom" + id);
        e.setPrenom("Prenom" + id);
        e.setNumero_inscription("INS-" + id);

        ExamenParticipation p = new ExamenParticipation();
        p.setId(id);
        p.setEst_present(true);
        p.setEtudiant(e);
        p.setNum_echantillon(String.valueOf(id));
        return p;
    }

    // =========================================================================
    // buildDashboard — resolveSessionStatut branches
    // =========================================================================

    @Nested
    @DisplayName("buildDashboard() — statuts de session")
    class BuildDashboardStatuts {
        /**
         * #209 — LA GARDE ANTI-IMPASSE du découplage valider/avancer, épinglée en unitaire
         * (elle n'était vérifiée qu'en live) : un groupe VALIDÉ dont le rang suivant n'est pas
         * encore ouvert reste la session EN_COURS. Sans cela, valider puis quitter l'écran
         * laissait l'accueil sans carte active — « Groupe suivant » vit DANS l'écran de
         * notation, l'évaluateur ne pouvait plus l'atteindre.
         */
        @Test
        @DisplayName("#209 : validé + rang suivant EN_ATTENTE → la session RESTE EN_COURS (anti-impasse)")
        void statut_termine_resteEnCoursTantQueLeRangSuivantNestPasOuvert() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(10));
            r.setStatut(RotationStatus.TERMINE);
            r.setOrdrePassage(1);
            Lot lot = lotFor(r, LotStatus.EN_COURS);
            Rotation suivante = new Rotation();
            suivante.setId(2L); suivante.setOrdrePassage(2);
            suivante.setStatut(RotationStatus.EN_ATTENTE);
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));
            when(rotationRepository
                    .findFirstByStationIdAndStudentGroup_Lot_IdAndOrdrePassageGreaterThanOrderByOrdrePassageAsc(
                            STATION_ID, lot.getId(), 1)).thenReturn(Optional.of(suivante));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions().get(0).getStatut()).isEqualTo("EN_COURS");
        }

        /** #209 — dès que le rang suivant est OUVERT, le groupe validé redevient TERMINEE. */
        @Test
        @DisplayName("#209 : validé + rang suivant EN_COURS → TERMINEE (la garde se referme)")
        void statut_termine_redevientTermineeQuandLeRangSuivantEstOuvert() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(10));
            r.setStatut(RotationStatus.TERMINE);
            r.setOrdrePassage(1);
            Lot lot = lotFor(r, LotStatus.EN_COURS);
            Rotation suivante = new Rotation();
            suivante.setId(2L); suivante.setOrdrePassage(2);
            suivante.setStatut(RotationStatus.EN_COURS);
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));
            when(rotationRepository
                    .findFirstByStationIdAndStudentGroup_Lot_IdAndOrdrePassageGreaterThanOrderByOrdrePassageAsc(
                            STATION_ID, lot.getId(), 1)).thenReturn(Optional.of(suivante));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions().get(0).getStatut()).isEqualTo("TERMINEE");
        }

        /** #209 — dernier passage validé, pas de rang suivant : TERMINEE, pas de fantôme. */
        @Test
        @DisplayName("#209 : validé sans rang suivant → TERMINEE")
        void statut_termine_sansRangSuivant() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(10));
            r.setStatut(RotationStatus.TERMINE);
            r.setOrdrePassage(2);
            Lot lot = lotFor(r, LotStatus.EN_COURS);
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));
            when(rotationRepository
                    .findFirstByStationIdAndStudentGroup_Lot_IdAndOrdrePassageGreaterThanOrderByOrdrePassageAsc(
                            STATION_ID, lot.getId(), 2)).thenReturn(Optional.empty());

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions().get(0).getStatut()).isEqualTo("TERMINEE");
        }

        @Test
        @DisplayName("A_VENIR : début dans le futur")
        void statut_aVenir() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).plusHours(2));
            lotFor(r, LotStatus.EN_COURS);
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions().get(0).getStatut()).isEqualTo("A_VENIR");
        }

        @Test
        @DisplayName("#207 EN_COURS : la rotation est stockée EN_COURS (l'heure ne joue aucun rôle)")
        void statut_enCours_vientDeLEtatStocke() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(20));
            r.setStatut(RotationStatus.EN_COURS);
            lotFor(r, LotStatus.EN_COURS);
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions().get(0).getStatut()).isEqualTo("EN_COURS");
        }

        /**
         * #207 / #238 — garde-fou anti-plafond.
         *
         * <p>Ancien comportement : passé {@code duree + 30 min}, la session basculait
         * TERMINEE toute seule et l'évaluateur perdait sa salle sans que personne n'ait
         * rien validé. Un examen qui dérive doit rester notable : seul un
         * {@code validerGroupe} termine un groupe.
         */
        @Test
        @DisplayName("#238 : une rotation partie il y a 60 min reste EN_COURS — l'horloge ne retire plus rien")
        void statut_enCours_survitALaDerive() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(60));
            r.setStatut(RotationStatus.EN_COURS);
            lotFor(r, LotStatus.EN_COURS);
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions().get(0).getStatut()).isEqualTo("EN_COURS");
        }

        @Test
        @DisplayName("TERMINEE : rotation explicitement marquée TERMINE (validerGroupe)")
        void statut_terminee_rotationExplicite() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(10));
            lotFor(r, LotStatus.EN_COURS);
            r.setStatut(RotationStatus.TERMINE);
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions().get(0).getStatut()).isEqualTo("TERMINEE");
        }

        @Test
        @DisplayName("Le statut du lot associé n'influence plus le statut de session (découplage)")
        void statutSession_independantDuStatutDuLot() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(5));
            r.setStatut(RotationStatus.EN_COURS);
            lotFor(r, LotStatus.TERMINE);
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions().get(0).getStatut()).isEqualTo("EN_COURS");
        }

        @Test
        @DisplayName("heureFin est toujours renseignée (debutCreneau + durée), quel que soit le statut")
        void heureFin_toujoursRenseignee() {
            Rotation rTerminee = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(60));
            Rotation rAVenir   = rotationAt(LocalDateTime.now(TUNIS).plusHours(1));
            rAVenir.setId(2L);
            rAVenir.setStationId(STATION_ID);
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(rTerminee, rAVenir));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions()).isNotEmpty();
            resp.getSessions().forEach(s -> assertThat(s.getHeureFin()).isNotNull());
        }

        @Test
        @DisplayName("Rotation sans stationId ou sans debutCreneau est ignorée")
        void rotation_sansChampObligatoire_ignoree() {
            Rotation sans = new Rotation();
            sans.setId(99L);
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(sans));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions()).isEmpty();
        }

        @Test
        @DisplayName("SessionResponse.id == id de la ROTATION (groupe courant), pas l'id du lot")
        void sessionId_estIdRotation() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(5));
            r.setId(777L);
            Lot lot = lotFor(r, LotStatus.EN_COURS);
            lot.setId(10L); // différent de 777 → prouve que ce n'est plus lot.getId()
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions().get(0).getId()).isEqualTo(777L);
        }
    }

    // =========================================================================
    // buildDashboard — temps effectif / pause (ADR-0012 §0)
    // =========================================================================

    @Nested
    @DisplayName("buildDashboard() — réconciliation du temps effectif (pause)")
    class EffectiveTimePauseReconciliation {

        private static final Long EXAMEN_ID = 99L;

        @BeforeEach
        void stubCommon() {
            lenient().when(examServiceClient.getStationInfo(STATION_ID))
                    .thenReturn(new ExamServiceClient.StationInfo("Station Test"));
        }

        /**
         * #207 — depuis que le statut est lu de l'état stocké, la pause ne se mesure plus
         * sur lui. Elle reste pourtant bien vivante : elle décale {@code debutPrevu},
         * l'ancre du compte à rebours mobile (le PLANCHER). C'est donc là qu'on l'observe.
         */
        private SessionResponse sessionOf(Rotation r) {
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));
            return service.buildDashboard(EVAL_ID).getSessions().get(0);
        }

        /**
         * Cette assertion portait sur le statut ({@code A_VENIR}) et passait encore après
         * #207 — mais À VIDE : la rotation est stockée EN_ATTENTE, donc A_VENIR sortait de
         * l'état stocké et non de la pause. Elle ne mesurait plus rien. Réancrée sur ce que
         * la pause déplace réellement : {@code debutPrevu} et le drapeau {@code enPause}.
         */
        @Test
        @DisplayName("#207 Pause active (10 min) : debutPrevu repoussé + enPause remonté au mobile")
        void pauseActive_repousseDebutPrevu() {
            LocalDateTime debut = LocalDateTime.now(TUNIS).minusMinutes(5);
            Rotation r = rotationAt(debut);
            r.setStatut(RotationStatus.EN_COURS);
            lotFor(r, LotStatus.EN_COURS);

            when(examServiceClient.getExamTiming(EXAMEN_ID)).thenReturn(
                    new ExamServiceClient.ExamTiming(
                            true, LocalDateTime.now(TUNIS).minusMinutes(10), 0, 15, 0, "EN_COURS"));

            SessionResponse s = sessionOf(r);
            assertThat(s.isEnPause()).isTrue();
            // ~10 min de pause en cours : tolérance d'une seconde sur l'horloge du test.
            assertThat(s.getDebutPrevu()).isBetween(debut.plusMinutes(10).minusSeconds(2),
                                                    debut.plusMinutes(10).plusSeconds(2));
        }

        @Test
        @DisplayName("#207 Pause cumulée (20 min) : debutPrevu est décalé d'autant — le PLANCHER survit")
        void pauseCumulee_decaleDebutPrevu() {
            LocalDateTime debut = LocalDateTime.now(TUNIS).minusMinutes(50);
            Rotation r = rotationAt(debut);
            r.setStatut(RotationStatus.EN_COURS);
            lotFor(r, LotStatus.EN_COURS);

            when(examServiceClient.getExamTiming(EXAMEN_ID)).thenReturn(
                    new ExamServiceClient.ExamTiming(false, null, 20 * 60, 15, 0, "EN_COURS"));

            SessionResponse s = sessionOf(r);
            // La pause repousse le début du passage : l'étudiant garde son temps.
            assertThat(s.getDebutPrevu()).isEqualTo(debut.plusMinutes(20));
            // …et ne touche plus au statut, qui vient de l'état stocké.
            assertThat(s.getStatut()).isEqualTo("EN_COURS");
        }

        @Test
        @DisplayName("#207 Durée lue de la config examen (60) : alimente dureeStationMin, plus aucune fenêtre")
        void dureeDepuisConfig_alimenteLePlancher() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(50));
            r.setStatut(RotationStatus.EN_COURS);
            lotFor(r, LotStatus.EN_COURS);

            when(examServiceClient.getExamTiming(EXAMEN_ID)).thenReturn(
                    new ExamServiceClient.ExamTiming(false, null, 0, 60, 0, "EN_COURS"));

            // La durée configurée est le temps DÛ à l'étudiant (PLANCHER), transmis au
            // compte à rebours mobile — elle n'élargit plus une fenêtre de retrait.
            assertThat(sessionOf(r).getDureeStationMin()).isEqualTo(60);
        }

        @Test
        @DisplayName("Timing neutre (exam-service injoignable) : l'examen est exclu du dashboard (aucune session)")
        void timingNeutre_aucuneSession() {
            when(examServiceClient.getExamTiming(EXAMEN_ID))
                    .thenReturn(ExamServiceClient.ExamTiming.neutral());

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions()).isEmpty();
        }
    }

    // =========================================================================
    // buildDashboard — projection du compte à rebours / avertissement (ADR-0012)
    // =========================================================================

    @Nested
    @DisplayName("buildDashboard() — debutPrevu / serverNow / enPause (ADR-0012)")
    class CountdownProjection {

        private static final Long EXAMEN_ID = 99L;

        @BeforeEach
        void stubCommon() {
            lenient().when(examServiceClient.getStationInfo(STATION_ID))
                    .thenReturn(new ExamServiceClient.StationInfo("Station Test"));
        }

        private SessionResponse sessionFor(Rotation r) {
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));
            return service.buildDashboard(EVAL_ID).getSessions().get(0);
        }

        @Test
        @DisplayName("serverNow renseigné sur l'enveloppe (≈ maintenant, zone horloge)")
        void serverNow_renseigne() {
            when(examServiceClient.getExamTiming(EXAMEN_ID))
                    .thenReturn(ExamServiceClient.ExamTiming.neutral());

            LocalDateTime serverNow = service.buildDashboard(EVAL_ID).getServerNow();

            assertThat(serverNow).isNotNull()
                    .isCloseTo(LocalDateTime.now(TUNIS),
                            within(1, java.time.temporal.ChronoUnit.MINUTES));
        }

        @Test
        @DisplayName("Sans pause : debutPrevu == debutCreneau (instant absolu inchangé)")
        void debutPrevu_sansPause_egalDebutCreneau() {
            LocalDateTime debut = LocalDateTime.now(TUNIS).plusMinutes(30);
            Rotation r = rotationAt(debut);
            lotFor(r, LotStatus.EN_COURS);
            when(examServiceClient.getExamTiming(EXAMEN_ID))
                    .thenReturn(new ExamServiceClient.ExamTiming(false, null, 0, 15, 0, "EN_COURS"));

            assertThat(sessionFor(r).getDebutPrevu()).isEqualTo(debut);
        }

        @Test
        @DisplayName("Pause cumulée : debutPrevu = debutCreneau + totalPauseSec (recule en heure murale)")
        void debutPrevu_avecPauseCumulee_decale() {
            LocalDateTime debut = LocalDateTime.now(TUNIS).plusMinutes(30);
            Rotation r = rotationAt(debut);
            lotFor(r, LotStatus.EN_COURS);
            when(examServiceClient.getExamTiming(EXAMEN_ID))
                    .thenReturn(new ExamServiceClient.ExamTiming(false, null, 10 * 60, 15, 0, "EN_COURS"));

            assertThat(sessionFor(r).getDebutPrevu()).isEqualTo(debut.plusMinutes(10));
        }

        @Test
        @DisplayName("Pause en cours : debutPrevu intègre aussi le temps de pause vivant")
        void debutPrevu_pauseEnCours_integreLive() {
            LocalDateTime debut = LocalDateTime.now(TUNIS).plusMinutes(30);
            Rotation r = rotationAt(debut);
            lotFor(r, LotStatus.EN_COURS);
            when(examServiceClient.getExamTiming(EXAMEN_ID)).thenReturn(
                    new ExamServiceClient.ExamTiming(
                            true, LocalDateTime.now(TUNIS).minusMinutes(5), 120, 15, 0, "EN_COURS"));

            LocalDateTime debutPrevu = sessionFor(r).getDebutPrevu();

            assertThat(debutPrevu).isCloseTo(debut.plusMinutes(7),
                    within(2, java.time.temporal.ChronoUnit.SECONDS));
        }

        @Test
        @DisplayName("enPause est échoé depuis l'état de l'examen ; avertissementLeadSec n'est plus propagé")
        void enPause_echoe_leadSecNonPropage() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).plusMinutes(30));
            lotFor(r, LotStatus.EN_COURS);
            when(examServiceClient.getExamTiming(EXAMEN_ID)).thenReturn(
                    new ExamServiceClient.ExamTiming(
                            true, LocalDateTime.now(TUNIS).minusMinutes(1), 0, 15, 90, "EN_COURS"));

            SessionResponse s = sessionFor(r);

            assertThat(s.getAvertissementLeadSec()).isZero();
            assertThat(s.isEnPause()).isTrue();
        }

        @Test
        @DisplayName("Timing neutre (exam-service injoignable) : l'examen n'est pas traité EN_COURS → aucune session")
        void timingNeutre_aucuneSession() {
            when(examServiceClient.getExamTiming(EXAMEN_ID))
                    .thenReturn(ExamServiceClient.ExamTiming.neutral());

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions()).isEmpty();
        }
    }

    // =========================================================================
    // buildDashboard — stats et planning
    // =========================================================================

    @Nested
    @DisplayName("buildDashboard() — stats et planning")
    class BuildDashboardMisc {
        @BeforeEach
        void setup() {
            lenient().when(examServiceClient.getStationInfo(anyLong()))
                    .thenReturn(new ExamServiceClient.StationInfo("Station Test"));
        }

        @Test
        @DisplayName("ADR-0015 — une station irrésolue dégrade SA session seule ; les sessions figées restent lisibles")
        void stationIrresolue_degradeSansTuerLeTableau() {
            // Régression live 2026-07-20 : pendant une panne d'exam-service, le repli-ouvert de
            // #241 fait remonter des sessions d'autres examens, jamais figées. Avec un resolve
            // strict sur le chemin de LECTURE, tout le dashboard tombait en HTTP 400 — y compris
            // les sessions parfaitement figées, ce qui contredit la promesse de l'ADR
            // (« après le premier succès, une panne d'exam-service est sans effet »).
            Lot lot = new Lot(); lot.setId(1L); lot.setExamenId(1L); lot.setNumeroLot(1);
            lot.setTailleLot(4); lot.setStatut(LotStatus.EN_COURS);

            Rotation figee    = rotationWithLot(1L, lot, 1);  // station 1 : figée
            Rotation parasite = rotationWithLot(2L, lot, 1);  // station 2 : jamais figée
            figee.setStationId(1L);
            parasite.setStationId(2L);
            // buildSessions ignore toute rotation sans debutCreneau.
            LocalDateTime debut = LocalDateTime.now(TUNIS).minusMinutes(5);
            figee.setDebutCreneau(debut);
            parasite.setDebutCreneau(debut);

            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(figee, parasite));
            when(examDefinitionSnapshot.resolveStationNomPourAffichage(any(), eq(1L)))
                    .thenReturn("Titrimétrie acido-basique");
            when(examDefinitionSnapshot.resolveStationNomPourAffichage(any(), eq(2L)))
                    .thenReturn(ExamDefinitionSnapshotService.NOM_INDISPONIBLE);

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions()).hasSize(2);
            assertThat(resp.getSessions())
                    .extracting(SessionResponse::getStationNom)
                    .containsExactlyInAnyOrder("Titrimétrie acido-basique",
                            ExamDefinitionSnapshotService.NOM_INDISPONIBLE);

            // Le marqueur dégradé doit rester HONNÊTE : ni vide (le mobile fait
            // `stationNom ?? ''`, un null rendrait un libellé blanc), ni plausible
            // (« Station 2 » se lirait comme un vrai intitulé — c'est le repli supprimé).
            assertThat(ExamDefinitionSnapshotService.NOM_INDISPONIBLE)
                    .isNotBlank()
                    .doesNotMatch("^Station \\d+$");
        }

        @Test
        @DisplayName("Stats : lots dérivés des rotations (resolverLotsDepuisRotations) — totalEtudiants, lotsValides")
        void stats_agregation() {
            Lot l1 = new Lot(); l1.setId(1L); l1.setExamenId(1L); l1.setNumeroLot(1);
            l1.setTailleLot(10); l1.setStatut(LotStatus.TERMINE);
            Lot l2 = new Lot(); l2.setId(2L); l2.setExamenId(1L); l2.setNumeroLot(2);
            l2.setTailleLot(8); l2.setStatut(LotStatus.EN_ATTENTE);

            Rotation r1 = rotationWithLot(1L, l1, 1);
            Rotation r2 = rotationWithLot(2L, l2, 1);

            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r1, r2));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getStats().getTotalEtudiants()).isEqualTo(18);
            assertThat(resp.getStats().getLotsValides()).isEqualTo(1);
            assertThat(resp.getStats().getTotalLots()).isEqualTo(2);
        }

        @Test
        @DisplayName("Deux rotations pointant vers le même lot ne comptent le lot qu'une fois (dédoublonnage)")
        void stats_dedoublonnageLot() {
            Lot lot = new Lot(); lot.setId(1L); lot.setExamenId(1L); lot.setNumeroLot(1);
            lot.setTailleLot(6); lot.setStatut(LotStatus.EN_ATTENTE);

            StudentGroup sg = new StudentGroup();
            sg.setLot(lot);
            Rotation r1 = new Rotation(); r1.setId(1L); r1.setStudentGroup(sg);
            Rotation r2 = new Rotation(); r2.setId(2L); r2.setStudentGroup(sg);
            sg.setRotations(List.of(r1, r2));

            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r1, r2));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getStats().getTotalLots()).isEqualTo(1);
            assertThat(resp.getStats().getTotalEtudiants()).isEqualTo(6);
        }

        @Test
        @DisplayName("Planning : filtre sur la date du jour, trié par heure")
        void planning_filtreSurJourJ() {
            LocalDateTime maintenant = LocalDateTime.now(TUNIS);

            Rotation rAujourdHui  = rotationAt(maintenant.withHour(9).withMinute(0));
            lotFor(rAujourdHui, LotStatus.EN_COURS);
            rAujourdHui.setStatut(RotationStatus.EN_ATTENTE);

            Rotation rDemain = rotationAt(maintenant.plusDays(1).withHour(9).withMinute(0));
            lotFor(rDemain, LotStatus.EN_COURS);
            rDemain.setId(2L); rDemain.setStationId(STATION_ID);

            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(rAujourdHui, rDemain));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getPlanning()).hasSize(1);
        }

        @Test
        @DisplayName("Planning statut TERMINE si rotation marquée TERMINE")
        void planning_statutTermine_siRotationTermine() {
            LocalDateTime maintenant = LocalDateTime.now(TUNIS);
            Rotation r = rotationAt(maintenant.withHour(8).withMinute(0));
            r.setStatut(RotationStatus.TERMINE);

            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getPlanning().get(0).getStatut()).isEqualTo("TERMINE");
        }

        @Test
        @DisplayName("Lot sans tailleLot (null) compte pour 0 dans totalEtudiants")
        void stats_tailleLotNull() {
            Lot lot = new Lot(); lot.setId(1L); lot.setExamenId(1L); lot.setNumeroLot(1);
            lot.setStatut(LotStatus.EN_ATTENTE);

            Rotation r = rotationWithLot(1L, lot, 1);
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getStats().getTotalEtudiants()).isZero();
        }
    }

    // =========================================================================
    // getGroupeDetail
    // =========================================================================

    @Nested
    @DisplayName("getGroupeDetail()")
    class GetGroupeDetail {

        /**
         * #209 — ouvrir l'écran DÉMARRE le minuteur : premier accès du propriétaire à une
         * rotation EN_COURS ⇒ debutReel horodaté (write-once). C'est le « Poursuivre la
         * notation » de Nada — l'ancre observée qui remplace le créneau planifié (12:51
         * restants sur une station de 2 min).
         */
        @Test
        @DisplayName("#209 : premier accès à une rotation EN_COURS → debutReel horodaté, write-once")
        void groupeDetail_horodateLeDebutReel_uneSeuleFois() {
            Lot lot = new Lot();
            lot.setId(10L); lot.setExamenId(99L); lot.setNumeroLot(1);
            lot.setStatut(LotStatus.EN_COURS);
            Rotation r = rotationWithLot(1L, lot, 1);
            r.setStatut(RotationStatus.EN_COURS);
            when(rotationRepository.findById(1L)).thenReturn(Optional.of(r));
            when(studentGroupRepository.findByLotId(10L)).thenReturn(List.of(new StudentGroup()));
            when(rotationAssignmentRepository.findByRotationId(1L)).thenReturn(List.of());

            assertThat(r.getDebutReel()).isNull();
            LotDetailResponse resp = service.getGroupeDetail(1L, EVAL_ID);

            assertThat(r.getDebutReel()).isNotNull();
            assertThat(resp.getDebutReel()).isEqualTo(r.getDebutReel());
            verify(rotationRepository).save(r);

            // Ré-accès : même horodatage, aucune réécriture (write-once).
            var premier = r.getDebutReel();
            service.getGroupeDetail(1L, EVAL_ID);
            assertThat(r.getDebutReel()).isEqualTo(premier);
            verify(rotationRepository, org.mockito.Mockito.times(1)).save(any(Rotation.class));
        }

        /** #209 — une rotation pas encore ouverte (EN_ATTENTE) n'est PAS horodatée. */
        @Test
        @DisplayName("#209 : rotation EN_ATTENTE consultée → pas d'horodatage")
        void groupeDetail_nHorodatePasUneRotationEnAttente() {
            Lot lot = new Lot();
            lot.setId(10L); lot.setExamenId(99L); lot.setNumeroLot(1);
            lot.setStatut(LotStatus.EN_COURS);
            Rotation r = rotationWithLot(1L, lot, 1);   // EN_ATTENTE par défaut
            when(rotationRepository.findById(1L)).thenReturn(Optional.of(r));
            when(studentGroupRepository.findByLotId(10L)).thenReturn(List.of(new StudentGroup()));
            when(rotationAssignmentRepository.findByRotationId(1L)).thenReturn(List.of());

            service.getGroupeDetail(1L, EVAL_ID);

            assertThat(r.getDebutReel()).isNull();
            verify(rotationRepository, never()).save(any(Rotation.class));
        }

        @Test
        @DisplayName("200 — retourne le groupe avec ses étudiants, scopé par rotationId")
        void groupeDetail_happy() {
            Lot lot = new Lot();
            lot.setId(10L); lot.setExamenId(99L); lot.setNumeroLot(2);
            lot.setTailleLot(2); lot.setStatut(LotStatus.EN_COURS);
            Rotation r = rotationWithLot(1L, lot, 3);

            ExamenParticipation p = participation(1L);
            RotationAssignment ra = new RotationAssignment();
            ra.setId(100L); ra.setParticipation(p); ra.setRotation(r);
            ra.setPresenceConfirmee(true);

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(r));
            when(studentGroupRepository.findByLotId(10L))
                    .thenReturn(List.of(new StudentGroup(), new StudentGroup(), new StudentGroup()));
            when(rotationAssignmentRepository.findByRotationId(1L)).thenReturn(List.of(ra));

            LotDetailResponse resp = service.getGroupeDetail(1L, EVAL_ID);

            assertThat(resp.getId()).isEqualTo(1L);       // id = rotation, pas lot
            assertThat(resp.getNumero()).isEqualTo(3);    // numéro du GROUPE
            assertThat(resp.getTotal()).isEqualTo(3);     // total de groupes du lot
            assertThat(resp.isValide()).isFalse();
            assertThat(resp.getEtudiants()).hasSize(1);
            assertThat(resp.getEtudiants().get(0).getNom()).isEqualTo("Nom1");
            assertThat(resp.getEtudiants().get(0).isAbsent()).isFalse();
        }

        @Test
        @DisplayName("Rotation TERMINE → valide = true")
        void groupeDetail_valide() {
            Lot lot = new Lot();
            lot.setId(5L); lot.setExamenId(1L); lot.setNumeroLot(1);
            lot.setStatut(LotStatus.EN_COURS);
            Rotation r = rotationWithLot(1L, lot, 1);
            r.setStatut(RotationStatus.TERMINE);

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(r));
            when(studentGroupRepository.findByLotId(5L)).thenReturn(List.of(new StudentGroup()));
            when(rotationAssignmentRepository.findByRotationId(1L)).thenReturn(List.of());

            LotDetailResponse resp = service.getGroupeDetail(1L, EVAL_ID);

            assertThat(resp.isValide()).isTrue();
        }

        @Test
        @DisplayName("Présence dérivée de RotationAssignment.presenceConfirmee (pas de la participation)")
        void groupeDetail_presenceParAssignment() {
            Lot lot = new Lot();
            lot.setId(5L); lot.setExamenId(1L); lot.setNumeroLot(1);
            lot.setStatut(LotStatus.EN_COURS);
            Rotation r = rotationWithLot(1L, lot, 1);

            ExamenParticipation p = participation(1L);
            p.setEst_present(true); // vrai pour l'examen dans son ensemble

            RotationAssignment ra = new RotationAssignment();
            ra.setId(50L); ra.setParticipation(p); ra.setRotation(r);
            ra.setPresenceConfirmee(false); // mais absent CETTE station

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(r));
            when(studentGroupRepository.findByLotId(5L)).thenReturn(List.of(new StudentGroup()));
            when(rotationAssignmentRepository.findByRotationId(1L)).thenReturn(List.of(ra));

            LotDetailResponse resp = service.getGroupeDetail(1L, EVAL_ID);

            assertThat(resp.getEtudiants().get(0).isAbsent()).isTrue();
        }

        @Test
        @DisplayName("Participation sans étudiant est filtrée")
        void groupeDetail_participationSansEtudiant() {
            Lot lot = new Lot();
            lot.setId(5L); lot.setExamenId(1L); lot.setNumeroLot(1);
            lot.setStatut(LotStatus.EN_ATTENTE);
            Rotation r = rotationWithLot(1L, lot, 1);

            ExamenParticipation orphan = new ExamenParticipation();
            orphan.setId(99L);
            orphan.setEtudiant(null);

            RotationAssignment ra = new RotationAssignment();
            ra.setParticipation(orphan);
            ra.setRotation(r);

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(r));
            when(studentGroupRepository.findByLotId(5L)).thenReturn(List.of(new StudentGroup()));
            when(rotationAssignmentRepository.findByRotationId(1L)).thenReturn(List.of(ra));

            LotDetailResponse resp = service.getGroupeDetail(1L, EVAL_ID);

            assertThat(resp.getEtudiants()).isEmpty();
        }

        @Test
        @DisplayName("Notation verrouillée → verrouille = true dans la réponse")
        void groupeDetail_notationVerrouillee() {
            Lot lot = new Lot();
            lot.setId(5L); lot.setExamenId(1L); lot.setNumeroLot(1);
            lot.setStatut(LotStatus.EN_ATTENTE);
            Rotation r = rotationWithLot(1L, lot, 1);

            ExamenParticipation p = participation(7L);
            RotationAssignment ra = new RotationAssignment();
            ra.setId(20L); ra.setParticipation(p); ra.setRotation(r);

            Notation n = new Notation();
            n.setId(30L); n.setVerouillee(true);

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(r));
            when(studentGroupRepository.findByLotId(5L)).thenReturn(List.of(new StudentGroup()));
            when(rotationAssignmentRepository.findByRotationId(1L)).thenReturn(List.of(ra));
            when(notationRepository.findByAssignmentId(20L)).thenReturn(Optional.of(n));
            lenient().when(notationItemRepository.findByNotationId(30L)).thenReturn(List.of());

            LotDetailResponse resp = service.getGroupeDetail(1L, EVAL_ID);

            assertThat(resp.getEtudiants().get(0).isVerrouille()).isTrue();
        }

        @Test
        @DisplayName("Rotation introuvable → ResourceNotFoundException")
        void groupeDetail_rotationIntrouvable() {
            when(rotationRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getGroupeDetail(99L, EVAL_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("introuvable");
        }

        @Test
        @DisplayName("Rotation d'un AUTRE évaluateur → AccessDeniedException")
        void groupeDetail_horsPerimetre() {
            Lot lot = new Lot(); lot.setId(5L); lot.setExamenId(1L); lot.setNumeroLot(1);
            Rotation r = rotationWithLot(1L, lot, 1);
            r.setEvaluateurId(999L); // un autre évaluateur

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.getGroupeDetail(1L, EVAL_ID))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Rotation sans groupe/lot associé → ResourceNotFoundException")
        void groupeDetail_sansLot() {
            Rotation r = new Rotation();
            r.setId(1L); r.setEvaluateurId(EVAL_ID); r.setStudentGroup(null);

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.getGroupeDetail(1L, EVAL_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // =========================================================================
    // avancerGroupe (ex-getGroupeSuivant)
    // =========================================================================

    @Nested
    @DisplayName("avancerGroupe()")
    class GetGroupeSuivant {

        /**
         * #209 — « Groupe suivant » est L'ACTE d'avancer, découplé de la validation : il
         * OUVRE le rang suivant (EN_COURS) et l'horodate ({@code debutReel}). La navigation
         * reste séquencée sur le RANG, bornée à (station, lot) — #248.
         */
        @Test
        @DisplayName("#209 : avancer OUVRE le rang suivant (EN_COURS + debutReel) et le renvoie")
        void groupeSuivant_happy() {
            Lot lot = new Lot();
            lot.setId(5L); lot.setExamenId(1L); lot.setNumeroLot(1);
            lot.setStatut(LotStatus.EN_COURS);

            Rotation courante = rotationWithLot(1L, lot, 1);
            courante.setOrdrePassage(1);

            Rotation suivante = rotationWithLot(2L, lot, 2);
            suivante.setOrdrePassage(2);

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(courante));
            when(rotationRepository
                    .findFirstByStationIdAndStudentGroup_Lot_IdAndOrdrePassageGreaterThanOrderByOrdrePassageAsc(
                            STATION_ID, 5L, 1)).thenReturn(Optional.of(suivante));
            when(studentGroupRepository.findByLotId(5L)).thenReturn(List.of(new StudentGroup(), new StudentGroup()));
            when(rotationAssignmentRepository.findByRotationId(2L)).thenReturn(List.of());

            LotDetailResponse resp = service.avancerGroupe(1L, EVAL_ID);

            assertThat(resp.getId()).isEqualTo(2L);
            assertThat(resp.getNumero()).isEqualTo(2);
            // #209 — l'acte : le rang suivant est OUVERT et son minuteur démarre MAINTENANT
            // (fait observé), pas au créneau planifié.
            assertThat(suivante.getStatut()).isEqualTo(RotationStatus.EN_COURS);
            assertThat(suivante.getDebutReel()).isNotNull();
            assertThat(resp.getDebutReel()).isEqualTo(suivante.getDebutReel());
            // Le créneau ne doit jouer aucun rôle : aucune requête horaire n'est consultée.
            verify(rotationRepository, never())
                    .findFirstByEvaluateurIdAndDebutCreneauAfterOrderByDebutCreneauAsc(any(), any());
        }

        @Test
        @DisplayName("Dernier passage de la station pour ce lot → ResourceNotFoundException")
        void groupeSuivant_aucunSuivant() {
            Lot lot = new Lot(); lot.setId(5L); lot.setExamenId(1L); lot.setNumeroLot(1);
            Rotation courante = rotationWithLot(1L, lot, 1);
            courante.setOrdrePassage(2);

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(courante));
            when(rotationRepository
                    .findFirstByStationIdAndStudentGroup_Lot_IdAndOrdrePassageGreaterThanOrderByOrdrePassageAsc(
                            STATION_ID, 5L, 2)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.avancerGroupe(1L, EVAL_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("dernier passage de cette station");
        }

        /**
         * #248 — le drapeau que le client utilise pour (dé)griser « Groupe suivant ».
         * Le bug venait de ce que le client le redéduisait de {@code numero >= total} : ici le
         * groupe courant EST le dernier groupe (2/2) et il RESTE pourtant un passage — c'est
         * exactement le cas que l'ancienne garde ratait, sur une station qui reçoit les
         * groupes dans l'ordre 2 puis 1 (carré latin).
         */
        @Test
        @DisplayName("#248 : groupeSuivantDisponible=true même quand le groupe courant est le dernier NUMÉRO")
        void groupeSuivant_drapeauIndependantDuNumeroDeGroupe() {
            Lot lot = new Lot(); lot.setId(5L); lot.setExamenId(1L); lot.setNumeroLot(1);
            Rotation courante = rotationWithLot(1L, lot, 2); // groupe n°2 …
            courante.setOrdrePassage(1);                     // … mais premier passage

            Rotation suivante = rotationWithLot(2L, lot, 1);
            suivante.setOrdrePassage(2);

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(courante));
            when(rotationRepository
                    .findFirstByStationIdAndStudentGroup_Lot_IdAndOrdrePassageGreaterThanOrderByOrdrePassageAsc(
                            STATION_ID, 5L, 1)).thenReturn(Optional.of(suivante));
            when(studentGroupRepository.findByLotId(5L)).thenReturn(List.of(new StudentGroup(), new StudentGroup()));
            when(rotationAssignmentRepository.findByRotationId(1L)).thenReturn(List.of());

            LotDetailResponse resp = service.getGroupeDetail(1L, EVAL_ID);

            assertThat(resp.getNumero()).isEqualTo(2);
            assertThat(resp.getTotal()).isEqualTo(2);   // numero >= total ⇒ ancienne garde : grisé
            assertThat(resp.isGroupeSuivantDisponible()).isTrue();  // … alors qu'un passage reste
        }

        @Test
        @DisplayName("Rotation courante d'un autre évaluateur → AccessDeniedException")
        void groupeSuivant_horsPerimetre() {
            Lot lot = new Lot(); lot.setId(5L); lot.setExamenId(1L); lot.setNumeroLot(1);
            Rotation courante = rotationWithLot(1L, lot, 1);
            courante.setEvaluateurId(999L);

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(courante));

            assertThatThrownBy(() -> service.avancerGroupe(1L, EVAL_ID))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Rotation courante introuvable → ResourceNotFoundException")
        void groupeSuivant_courantIntrouvable() {
            when(rotationRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.avancerGroupe(1L, EVAL_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // =========================================================================
    // saisirNotation
    // =========================================================================

    @Nested
    @DisplayName("saisirNotation()")
    class SaisirNotation {

        @Test
        @DisplayName("Participation introuvable (même après repli findByEtudiantId) → ResourceNotFoundException")
        void participationIntrouvable() {
            SaisirNotationRequest req = new SaisirNotationRequest(1L, STATION_ID, 1L, 1L, 10f);
            when(participationRepository.findByEtudiantIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.saisirNotation(req, EVAL_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Inexistant");
        }

        @Test
        @DisplayName("Participation résolue via le repli findByEtudiantId quand aucune ne correspond à la station")
        void saisirNotation_fallbackParticipation() {
            ExamenParticipation p = participation(2L); p.setId(150L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(250L);
            Notation n = new Notation(); n.setId(2L); n.setGrilleId(1L); n.setVerouillee(false);

            when(participationRepository.findByEtudiantIdAndStationId(2L, STATION_ID))
                    .thenReturn(Optional.empty());
            when(participationRepository.findByEtudiantId(2L)).thenReturn(List.of(p));
            when(rotationAssignmentRepository.findByParticipationIdAndStationId(150L, STATION_ID)).thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(250L)).thenReturn(Optional.of(n));
            when(notationItemRepository.findByNotationIdAndItemId(2L, 5L)).thenReturn(Optional.empty());
            when(notationItemRepository.findByNotationId(2L)).thenReturn(List.of());
            when(examDefinitionSnapshot.resolveItems(any(), eq(1L)))
                    .thenReturn(definition(5L, 1.0, "NUMERIQUE"));

            service.saisirNotation(new SaisirNotationRequest(2L, STATION_ID, 1L, 5L, 1.0f), EVAL_ID);

            verify(notationItemRepository).save(any(NotationItem.class));
        }

        /**
         * #213 — l'auteur doit être ENREGISTRÉ, pas déduit.
         *
         * <p>Avant, « qui a noté ? » se lisait sur {@code rotation.evaluateur_id},
         * donc sur le propriétaire de la station : toute saisie faite par
         * quelqu'un d'autre était attribuée au mauvais évaluateur. Reproduit en
         * direct sur l'examen 53 (note 9.5 saisie par l'évaluateur 6, attribuée
         * au 3). Une traçabilité fausse est pire qu'absente — elle accuse.
         */
        @Test
        @DisplayName("#213/ADR-0017 — la note retient QUI l'a saisie : après suppléance, c'est le REMPLAÇANT")
        void saisirNotation_devraitEnregistrerLAuteurReel() {
            // Le remplaçant : la suppléance lui a réaffecté la rotation, il passe donc
            // le garde. Sans cet enregistrement, sa note serait attribuée au partant.
            final Long REMPLACANT = 999L;
            when(rotationRepository.existsByEvaluateurIdAndStationId(REMPLACANT, STATION_ID))
                    .thenReturn(true);
            ExamenParticipation p = participation(1L); p.setId(100L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(200L);
            Notation n = new Notation(); n.setId(1L); n.setGrilleId(1L); n.setVerouillee(false);

            when(participationRepository.findByEtudiantIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationIdAndStationId(100L, STATION_ID))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(200L)).thenReturn(Optional.of(n));
            when(notationItemRepository.findByNotationIdAndItemId(1L, 5L)).thenReturn(Optional.empty());
            when(notationItemRepository.findByNotationId(1L)).thenReturn(List.of());
            when(examDefinitionSnapshot.resolveItems(any(), eq(1L)))
                    .thenReturn(definition(5L, 1.0, "NUMERIQUE"));

            service.saisirNotation(
                    new SaisirNotationRequest(1L, STATION_ID, 1L, 5L, 9.5f), REMPLACANT);

            // Le fait, pas la déduction : c'est bien l'appelant qui est retenu.
            assertThat(n.getSaisiPar()).isEqualTo(REMPLACANT);
        }

        /**
         * #213 — LE trou : reproduit en direct avant correctif (l'évaluateur 6 a
         * noté 9.5 sur la station 87, qui appartient au 3, HTTP 200).
         *
         * <p>Un garde existait pourtant — {@code createAssignment} exige une
         * rotation (évaluateur, station) — mais uniquement sur le chemin FROID,
         * emprunté seulement si l'assignment n'existe pas encore. Or
         * {@code presence-et-demarrer} les crée tous au démarrage du lot : en
         * conditions réelles il ne tournait jamais. D'où un garde explicite en
         * tête de méthode, avant toute résolution.
         */
        @Test
        @DisplayName("#213 — noter sur la station d'un AUTRE évaluateur est refusé")
        void saisirNotation_surStationDAutrui_devraitEtreRefuse() {
            final Long INTRUS = 6L;
            when(rotationRepository.existsByEvaluateurIdAndStationId(INTRUS, STATION_ID))
                    .thenReturn(false); // il ne tient pas cette station

            SaisirNotationRequest req = new SaisirNotationRequest(1L, STATION_ID, 1L, 5L, 9.5f);
            assertThatThrownBy(() -> service.saisirNotation(req, INTRUS))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("pas affecté à la station");

            // Rien n'a été écrit : le garde tombe AVANT toute résolution.
            verifyNoInteractions(notationItemRepository);
        }

        @Test
        @DisplayName("#213 — verrouiller la note d'un étudiant sur la station d'autrui est refusé")
        void validerEtudiant_surStationDAutrui_devraitEtreRefuse() {
            final Long INTRUS = 6L;
            when(rotationRepository.existsByEvaluateurIdAndStationId(INTRUS, STATION_ID))
                    .thenReturn(false);

            ValiderEtudiantRequest req = new ValiderEtudiantRequest();
            assertThatThrownBy(() -> service.validerEtudiant(1L, STATION_ID, INTRUS, req))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Notation verrouillée → BusinessException")
        void notationVerrouillee() {
            ExamenParticipation p = participation(1L); p.setId(100L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(200L);
            Notation n = new Notation(); n.setId(1L); n.setGrilleId(1L); n.setVerouillee(true);

            when(participationRepository.findByEtudiantIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationIdAndStationId(100L, STATION_ID))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(200L)).thenReturn(Optional.of(n));
            // L'item EST notable : on veut que le rejet vienne bien du VERROU et non de la
            // garde ADR-0015, sinon ce test ne prouverait plus rien sur le verrouillage.
            when(examDefinitionSnapshot.resolveItems(any(), eq(1L)))
                    .thenReturn(definition(5L, 1.0, "NUMERIQUE"));

            SaisirNotationRequest req = new SaisirNotationRequest(1L, STATION_ID, 1L, 5L, 1.0f);

            assertThatThrownBy(() -> service.saisirNotation(req, EVAL_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Verrouillé");
        }

        @Test
        @DisplayName("Item existant → mise à jour de la valeur (upsert UPDATE)")
        void saisirNotation_updateItemExistant() {
            ExamenParticipation p = participation(1L); p.setId(100L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(200L);
            Notation n = new Notation(); n.setId(1L); n.setGrilleId(1L); n.setVerouillee(false);

            NotationItem existingItem = new NotationItem();
            existingItem.setId(50L); existingItem.setItemId(5L); existingItem.setValeur(0f);
            existingItem.setNotation(n);

            when(participationRepository.findByEtudiantIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationIdAndStationId(100L, STATION_ID))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(200L)).thenReturn(Optional.of(n));
            when(notationItemRepository.findByNotationIdAndItemId(1L, 5L))
                    .thenReturn(Optional.of(existingItem));
            when(notationItemRepository.findByNotationId(1L)).thenReturn(List.of(existingItem));
            when(examDefinitionSnapshot.resolveItems(any(), eq(1L)))
                    .thenReturn(definition(5L, 1.0, "NUMERIQUE"));

            SaisirNotationRequest req = new SaisirNotationRequest(1L, STATION_ID, 1L, 5L, 3.0f);
            service.saisirNotation(req, EVAL_ID);

            assertThat(existingItem.getValeur()).isEqualTo(3.0f);
            verify(notationItemRepository).save(existingItem);
        }

        @Test
        @DisplayName("Item inexistant → création d'un nouveau NotationItem (upsert INSERT)")
        void saisirNotation_creerNouvelItem() {
            ExamenParticipation p = participation(1L); p.setId(100L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(200L);
            Notation n = new Notation(); n.setId(1L); n.setGrilleId(1L); n.setVerouillee(false);

            when(participationRepository.findByEtudiantIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationIdAndStationId(100L, STATION_ID))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(200L)).thenReturn(Optional.of(n));
            when(notationItemRepository.findByNotationIdAndItemId(1L, 7L))
                    .thenReturn(Optional.empty());

            lenient().when(notationItemRepository.findByNotationId(1L)).thenReturn(List.of());
            lenient().when(examDefinitionSnapshot.resolveItems(any(), eq(1L)))
                    .thenReturn(definition(7L, 1.0, "NUMERIQUE"));

            SaisirNotationRequest req = new SaisirNotationRequest(1L, STATION_ID, 1L, 7L, 2.0f);
            service.saisirNotation(req, EVAL_ID);

            verify(notationItemRepository, times(1)).save(any(NotationItem.class));
        }

        @Test
        @DisplayName("Pas d'assignment existant → createAssignment est appelé")
        void saisirNotation_creationAssignment() {
            ExamenParticipation p = participation(1L); p.setId(100L);
            Rotation rotation = new Rotation(); rotation.setId(300L);
            RotationAssignment savedRa = new RotationAssignment(); savedRa.setId(400L);
            Notation n = new Notation(); n.setId(1L); n.setGrilleId(1L); n.setVerouillee(false);

            when(participationRepository.findByEtudiantIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationIdAndStationId(100L, STATION_ID))
                    .thenReturn(Optional.empty());
            when(rotationRepository.findFirstByEvaluateurIdAndStationIdOrderByIdDesc(EVAL_ID, STATION_ID))
                    .thenReturn(Optional.of(rotation));

            lenient().when(rotationAssignmentRepository.save(any(RotationAssignment.class))).thenReturn(savedRa);
            lenient().when(notationRepository.findByAssignmentId(anyLong())).thenReturn(Optional.of(n));
            lenient().when(notationItemRepository.findByNotationIdAndItemId(anyLong(), anyLong()))
                    .thenReturn(Optional.empty());
            lenient().when(notationItemRepository.findByNotationId(anyLong())).thenReturn(List.of());
            lenient().when(examDefinitionSnapshot.resolveItems(any(), eq(1L)))
                    .thenReturn(definition(5L, 1.0, "NUMERIQUE"));

            service.saisirNotation(new SaisirNotationRequest(1L, STATION_ID, 1L, 5L, 1.0f), EVAL_ID);

            verify(rotationAssignmentRepository).save(any(RotationAssignment.class));
        }

        @Test
        @DisplayName("Score BINAIRE : valeur × pondération")
        void scoreCalcul_binaire() {
            ExamenParticipation p = participation(1L); p.setId(100L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(200L);
            Notation n = new Notation(); n.setId(1L); n.setGrilleId(1L); n.setVerouillee(false);
            NotationItem ni = new NotationItem();
            ni.setItemId(5L); ni.setValeur(1.0f); ni.setNotation(n);

            when(participationRepository.findByEtudiantIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationIdAndStationId(100L, STATION_ID))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(200L)).thenReturn(Optional.of(n));
            when(notationItemRepository.findByNotationIdAndItemId(1L, 5L))
                    .thenReturn(Optional.of(ni));
            when(notationItemRepository.findByNotationId(1L)).thenReturn(List.of(ni));
            when(examDefinitionSnapshot.resolveItems(any(), eq(1L)))
                    .thenReturn(definition(5L, 4.0, "BINAIRE"));

            service.saisirNotation(new SaisirNotationRequest(1L, STATION_ID, 1L, 5L, 1.0f), EVAL_ID);

            assertThat(n.getScore_final()).isEqualTo(4.0f);
        }

        @Test
        @DisplayName("Score NUMERIQUE : valeur directe sans multiplication")
        void scoreCalcul_numerique() {
            ExamenParticipation p = participation(1L); p.setId(100L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(200L);
            Notation n = new Notation(); n.setId(1L); n.setGrilleId(1L); n.setVerouillee(false);
            NotationItem ni = new NotationItem();
            ni.setItemId(5L); ni.setValeur(3.5f); ni.setNotation(n);

            when(participationRepository.findByEtudiantIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationIdAndStationId(100L, STATION_ID))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(200L)).thenReturn(Optional.of(n));
            when(notationItemRepository.findByNotationIdAndItemId(1L, 5L))
                    .thenReturn(Optional.of(ni));
            when(notationItemRepository.findByNotationId(1L)).thenReturn(List.of(ni));
            when(examDefinitionSnapshot.resolveItems(any(), eq(1L)))
                    .thenReturn(definition(5L, 10.0, "NUMERIQUE"));

            service.saisirNotation(new SaisirNotationRequest(1L, STATION_ID, 1L, 5L, 3.5f), EVAL_ID);

            assertThat(n.getScore_final()).isEqualTo(3.5f);
        }

        @Test
        @DisplayName("ADR-0015 / #240 — item hors définition figée : ÉCHEC FORT, aucun score faux persisté")
        void scoreCalcul_itemHorsSnapshot_echoueFort() {
            // Régression #240. Cette assertion était écrite À L'ENVERS : elle exigeait
            // « exam-service indisponible → somme des valeurs brutes », c'est-à-dire elle
            // consacrait le bug. Un item BINAIRE valeur 1 × pondération 5 valait alors 1 au
            // lieu de 5, score_final persisté et diffusé en WebSocket sans aucune erreur.
            // La règle ADR-0015 est l'inverse : on refuse la note plutôt que d'en écrire une fausse.
            ExamenParticipation p = participation(1L); p.setId(100L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(200L);
            Notation n = new Notation(); n.setId(1L); n.setGrilleId(1L); n.setVerouillee(false);

            when(participationRepository.findByEtudiantIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(p));
            // Volontairement `lenient` : la garde ADR-0015 rejette AVANT même de résoudre
            // l'assignment — c'est le comportement voulu (on ne touche à rien).
            lenient().when(rotationAssignmentRepository.findByParticipationIdAndStationId(100L, STATION_ID))
                    .thenReturn(Optional.of(ra));
            lenient().when(notationRepository.findByAssignmentId(200L)).thenReturn(Optional.of(n));
            // La grille ne déclare notable que l'item 9 — l'item 5 saisi n'en fait pas partie.
            when(examDefinitionSnapshot.resolveItems(any(), eq(1L)))
                    .thenReturn(definition(9L, 5.0, "BINAIRE"));

            SaisirNotationRequest req = new SaisirNotationRequest(1L, STATION_ID, 1L, 5L, 1.0f);

            assertThatThrownBy(() -> service.saisirNotation(req, EVAL_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("n'est pas un critère notable");

            // Le fond de l'affaire : rien n'est écrit, donc aucune note fausse ne survit.
            assertThat(n.getScore_final()).isNull();
            verify(notationRepository, never()).save(any(Notation.class));
            verify(notationItemRepository, never()).save(any(NotationItem.class));
        }

        @Test
        @DisplayName("ADR-0015 — la garde feuille ne se désactive plus sur définition vide (fail-open supprimé)")
        void gardeFeuille_inconditionnelle() {
            // Ancien comportement : `!feuillesValides.isEmpty() && ...` — une réponse vide
            // d'exam-service désactivait la garde, laissant noter un critère PARENT dont la
            // ligne se double-comptait ensuite définitivement dans recalculerScoreFinal.
            ExamenParticipation p = participation(1L); p.setId(100L);

            when(participationRepository.findByEtudiantIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(p));
            when(examDefinitionSnapshot.resolveItems(any(), eq(1L))).thenReturn(Map.of());

            SaisirNotationRequest req = new SaisirNotationRequest(1L, STATION_ID, 1L, 5L, 1.0f);

            assertThatThrownBy(() -> service.saisirNotation(req, EVAL_ID))
                    .isInstanceOf(BusinessException.class);
            verify(notationItemRepository, never()).save(any(NotationItem.class));
        }

        @Test
        @DisplayName("Aucun item après saisie (liste vide) → score = 0")
        void scoreCalcul_aucunItem() {
            ExamenParticipation p = participation(1L); p.setId(100L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(200L);
            Notation n = new Notation(); n.setId(1L); n.setGrilleId(1L); n.setVerouillee(false);

            when(participationRepository.findByEtudiantIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationIdAndStationId(100L, STATION_ID))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(200L)).thenReturn(Optional.of(n));
            when(notationItemRepository.findByNotationIdAndItemId(1L, 5L))
                    .thenReturn(Optional.empty());
            when(notationItemRepository.findByNotationId(1L)).thenReturn(List.of());
            when(examDefinitionSnapshot.resolveItems(any(), eq(1L)))
                    .thenReturn(definition(5L, 1.0, "NUMERIQUE"));

            service.saisirNotation(new SaisirNotationRequest(1L, STATION_ID, 1L, 5L, 1.0f), EVAL_ID);

            assertThat(n.getScore_final()).isEqualTo(0.0f);
        }
    }

    // =========================================================================
    // validerEtudiant
    //
    // #FIX multi-station : la présence par station vit désormais sur
    // RotationAssignment.presenceConfirmee, plus sur ExamenParticipation
    // (une seule ligne par (étudiant, examen) ne peut pas porter 4 présences
    // différentes, une par station). participation.est_present n'est plus
    // écrasé ici ; participation.note est recalculée comme la SOMME des
    // score_final de toutes les stations notées (#212), jamais le score d'une
    // seule station (l'ancien clobber).
    // =========================================================================

    @Nested
    @DisplayName("validerEtudiant()")
    class ValiderEtudiantLogic {

        @Test
        @DisplayName("Absent : score = 0, notation verrouillée, assignment.presenceConfirmee = false")
        void validerEtudiant_absent() {
            ExamenParticipation p = participation(1L); p.setId(1L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(55L);
            Notation n = new Notation(); n.setId(10L);

            when(participationRepository.findByEtudiantIdAndStationId(anyLong(), anyLong()))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(55L)).thenReturn(Optional.of(n));
            when(notationItemRepository.findByNotationId(10L)).thenReturn(List.of(new NotationItem()));

            ValiderEtudiantRequest req = new ValiderEtudiantRequest();
            req.setAbsent(true);
            req.setGrilleId(1L);
            service.validerEtudiant(1L, STATION_ID, EVAL_ID, req);

            assertThat(n.getScore_final()).isEqualTo(0.0f);
            assertThat(n.getVerouillee()).isTrue();
            assertThat(ra.getPresenceConfirmee()).isFalse();
            verify(notationItemRepository).deleteAll(anyList());
            verify(rotationAssignmentRepository).save(ra);
        }

        @Test
        @DisplayName("Présent : notation verrouillée, assignment.presenceConfirmee = true")
        void validerEtudiant_present() {
            ExamenParticipation p = participation(1L); p.setId(1L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(55L);
            Notation n = new Notation(); n.setId(10L); n.setScore_final(12f);

            when(participationRepository.findByEtudiantIdAndStationId(anyLong(), anyLong()))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(55L)).thenReturn(Optional.of(n));

            ValiderEtudiantRequest req = new ValiderEtudiantRequest();
            req.setAbsent(false);
            req.setGrilleId(1L);
            service.validerEtudiant(1L, STATION_ID, EVAL_ID, req);

            assertThat(n.getVerouillee()).isTrue();
            assertThat(ra.getPresenceConfirmee()).isTrue();
            verify(rotationAssignmentRepository).save(ra);
            verify(participationRepository).save(p);
        }

        @Test
        @DisplayName("#212 note agrégée : participation.note = SOMME des score_final de toutes les stations notées")
        void validerEtudiant_noteAgregeeCrossStation() {
            ExamenParticipation p = participation(1L); p.setId(1L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(55L);
            Notation courante = new Notation(); courante.setId(10L); courante.setScore_final(7f);

            when(participationRepository.findByEtudiantIdAndStationId(anyLong(), anyLong()))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(55L)).thenReturn(Optional.of(courante));
            // Deux stations déjà notées pour CETTE participation : 7 (station courante) + 12.
            Notation autreStation = new Notation(); autreStation.setId(11L); autreStation.setScore_final(12f);
            when(notationRepository.findByParticipationId(1L))
                    .thenReturn(List.of(courante, autreStation));

            ValiderEtudiantRequest req = new ValiderEtudiantRequest();
            req.setAbsent(false);
            req.setGrilleId(1L);
            service.validerEtudiant(1L, STATION_ID, EVAL_ID, req);

            // 7 + 12 = 19, PAS 7 (pas de clobber par la seule station courante).
            assertThat(p.getNote()).isEqualTo(19f);
            verify(participationRepository).save(p);
        }

        /**
         * #212 (dernier volet) — RÉÉCRIT, pas restauré : l'ancienne version affirmait
         * l'écriture sur la PARTICIPATION — c'est-à-dire le clobber lui-même (une ligne
         * partagée entre N stations, la dernière validation écrasait les autres, comme
         * jadis est_present et note). Le commentaire vit désormais sur la Notation,
         * par (participation, station), et la participation n'est plus touchée.
         */
        @Test
        @DisplayName("#212 : le commentaire vit sur la NOTATION (par station), plus sur la participation")
        void validerEtudiant_commentaireEnregistre() {
            ExamenParticipation p = participation(1L); p.setId(1L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(55L);
            Notation n = new Notation(); n.setId(10L);

            when(participationRepository.findByEtudiantIdAndStationId(anyLong(), anyLong()))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(55L)).thenReturn(Optional.of(n));

            ValiderEtudiantRequest req = new ValiderEtudiantRequest();
            req.setAbsent(false);
            req.setGrilleId(1L);
            req.setCommentaire("Bonne manipulation");
            service.validerEtudiant(1L, STATION_ID, EVAL_ID, req);

            assertThat(n.getCommentaire()).isEqualTo("Bonne manipulation");
            assertThat(p.getCommentaire()).isNull();   // la ligne partagée n'est PLUS écrite
        }

        /**
         * #212 — LE test du clobber : deux stations, deux commentaires. Sous l'ancien
         * modèle, la validation de la station B écrasait le commentaire de la station A
         * sur la ligne partagée. Ici chacun survit sur SA notation.
         */
        @Test
        @DisplayName("#212 : deux stations → deux commentaires, aucun n'écrase l'autre")
        void validerEtudiant_commentairesParStationNeSEcrasentPas() {
            ExamenParticipation p = participation(1L); p.setId(1L);
            RotationAssignment raA = new RotationAssignment(); raA.setId(55L);
            RotationAssignment raB = new RotationAssignment(); raB.setId(56L);
            Notation nA = new Notation(); nA.setId(10L);
            Notation nB = new Notation(); nB.setId(11L);

            // #213 — ce test valide sur DEUX stations : l'évaluateur doit tenir les
            // deux, sinon le garde d'écriture tombe sur la seconde (station 99).
            when(rotationRepository.existsByEvaluateurIdAndStationId(EVAL_ID, 99L))
                    .thenReturn(true);

            when(participationRepository.findByEtudiantIdAndStationId(anyLong(), anyLong()))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(raA));
            when(rotationAssignmentRepository.findByParticipationIdAndStationId(1L, 99L))
                    .thenReturn(Optional.of(raB));
            when(notationRepository.findByAssignmentId(55L)).thenReturn(Optional.of(nA));
            when(notationRepository.findByAssignmentId(56L)).thenReturn(Optional.of(nB));

            ValiderEtudiantRequest reqA = new ValiderEtudiantRequest();
            reqA.setAbsent(false); reqA.setGrilleId(1L);
            reqA.setCommentaire("Station A : geste précis");
            service.validerEtudiant(1L, STATION_ID, EVAL_ID, reqA);

            ValiderEtudiantRequest reqB = new ValiderEtudiantRequest();
            reqB.setAbsent(false); reqB.setGrilleId(2L);
            reqB.setCommentaire("Station B : dosage hésitant");
            service.validerEtudiant(1L, 99L, EVAL_ID, reqB);

            assertThat(nA.getCommentaire()).isEqualTo("Station A : geste précis");
            assertThat(nB.getCommentaire()).isEqualTo("Station B : dosage hésitant");
            assertThat(p.getCommentaire()).isNull();
        }

        @Test
        @DisplayName("validerEtudiant déclenche le broadcast du score via WebSocket")
        void validerEtudiant_broadcastScore() {
            ExamenParticipation p = participation(1L); p.setId(1L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(55L);
            ra.setParticipation(p);
            Notation n = new Notation(); n.setId(10L); n.setScore_final(8f); n.setAssignment(ra);

            when(participationRepository.findByEtudiantIdAndStationId(anyLong(), anyLong()))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(55L)).thenReturn(Optional.of(n));

            ValiderEtudiantRequest req = new ValiderEtudiantRequest();
            req.setAbsent(false);
            req.setGrilleId(1L);
            service.validerEtudiant(1L, STATION_ID, EVAL_ID, req);

            verify(messagingTemplate).convertAndSend(
                    eq("/topic/stations/" + STATION_ID + "/scores"), any(Object.class));
        }

        @Test
        @DisplayName("Participation introuvable (repli findByEtudiantId aussi vide) → ResourceNotFoundException")
        void validerEtudiant_participationIntrouvable() {
            when(participationRepository.findByEtudiantIdAndStationId(99L, STATION_ID))
                    .thenReturn(Optional.empty());

            ValiderEtudiantRequest req = new ValiderEtudiantRequest();
            req.setGrilleId(1L);

            assertThatThrownBy(() -> service.validerEtudiant(99L, STATION_ID, EVAL_ID, req))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Inexistant");
        }

        @Test
        @DisplayName("Absent sans items existants : deleteAll est quand même invoqué avec une liste vide")
        void validerEtudiant_absent_sansItems() {
            ExamenParticipation p = participation(1L); p.setId(1L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(55L);
            Notation n = new Notation(); n.setId(10L);

            when(participationRepository.findByEtudiantIdAndStationId(anyLong(), anyLong()))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(55L)).thenReturn(Optional.of(n));
            when(notationItemRepository.findByNotationId(10L)).thenReturn(List.of());

            ValiderEtudiantRequest req = new ValiderEtudiantRequest();
            req.setAbsent(true); req.setGrilleId(1L);
            service.validerEtudiant(1L, STATION_ID, EVAL_ID, req);

            verify(notationItemRepository).deleteAll(List.of());
            assertThat(n.getScore_final()).isEqualTo(0.0f);
        }
    }

    // =========================================================================
    // validerGroupe — remplace la validation côté évaluateur (ex "validerLot"
    // appelée depuis le mobile). Clôture la ROTATION (groupe × station
    // courants), puis clôture automatiquement le LOT si c'était sa dernière
    // rotation active.
    // =========================================================================

    @Nested
    @DisplayName("validerGroupe()")
    class ValiderGroupeLogic {

        /**
         * #209 — RÉÉCRIT, pas restauré : la version #207 de ce test exigeait que valider
         * OUVRE le rang suivant. C'était le couplage qui « déplaçait » l'évaluateur : valider
         * puis quitter l'écran ⇒ au retour, un AUTRE groupe, grille vide (vécu par Nada).
         * Règle actée 2026-07-23 : valider = verrouiller, point ; seul le clic explicite
         * « Groupe suivant » ({@code avancerGroupe}) avance. EN_COURS garde ses écrivains
         * légitimes : l'ouverture de vague (LotOuvertureService) et l'avance explicite.
         */
        @Test
        @DisplayName("#209 : valider VERROUILLE mais n'ouvre PAS le rang suivant")
        void validerGroupe_nAvancePlus() {
            Lot lot = new Lot(); lot.setId(10L); lot.setStatut(LotStatus.EN_COURS);
            Rotation courante = rotationWithLot(1L, lot, 1);
            courante.setOrdrePassage(1);
            courante.setStatut(RotationStatus.EN_COURS);

            Rotation suivante = rotationWithLot(2L, lot, 2);
            suivante.setOrdrePassage(2);

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(courante));
            when(rotationAssignmentRepository.findByRotationId(1L)).thenReturn(List.of());
            when(rotationRepository.countByStudentGroup_Lot_IdAndStatutNot(10L, RotationStatus.TERMINE))
                    .thenReturn(3L);

            service.validerGroupe(1L, EVAL_ID);

            assertThat(courante.getStatut()).isEqualTo(RotationStatus.TERMINE);
            // Le rang suivant n'a PAS bougé : il attend le clic de l'évaluateur.
            assertThat(suivante.getStatut()).isEqualTo(RotationStatus.EN_ATTENTE);
            verify(rotationRepository, never()).save(suivante);
        }

        @Test
        @DisplayName("Groupe validé, mais d'autres rotations du lot restent actives → lot NON clôturé")
        void validerGroupe_lotResteOuvert() {
            Lot lot = new Lot(); lot.setId(10L); lot.setStatut(LotStatus.EN_COURS);
            Rotation r = rotationWithLot(1L, lot, 1);

            RotationAssignment ra = new RotationAssignment(); ra.setId(50L);
            Notation n = new Notation(); n.setId(5L); n.setVerouillee(false);

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(r));
            when(rotationAssignmentRepository.findByRotationId(1L)).thenReturn(List.of(ra));
            when(notationRepository.findByAssignmentId(50L)).thenReturn(Optional.of(n));
            when(rotationRepository.countByStudentGroup_Lot_IdAndStatutNot(10L, RotationStatus.TERMINE))
                    .thenReturn(2L); // encore 2 rotations non terminées

            service.validerGroupe(1L, EVAL_ID);

            assertThat(r.getStatut()).isEqualTo(RotationStatus.TERMINE);
            assertThat(n.getVerouillee()).isTrue();
            verify(rotationRepository).save(r);
            verify(lotRepository, never()).save(any());
            verify(messagingTemplate).convertAndSend(eq("/topic/lots/10/status"), any(Object.class));
        }

        @Test
        @DisplayName("Dernière rotation active du lot → le lot est clôturé automatiquement (TERMINE)")
        void validerGroupe_dernierGroupe_clotureLot() {
            // 1. Setup
            Lot lot = new Lot();
            lot.setId(10L);
            lot.setStatut(LotStatus.EN_COURS);
            Rotation r = rotationWithLot(1L, lot, 4);

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(r));
            when(rotationAssignmentRepository.findByRotationId(1L)).thenReturn(List.of());

            // Simule que c'est la toute dernière rotation
            when(rotationRepository.countByStudentGroup_Lot_IdAndStatutNot(10L, RotationStatus.TERMINE))
                    .thenReturn(0L);

            // 2. Execution
            service.validerGroupe(1L, EVAL_ID);

            // 3. Verifications
            assertThat(r.getStatut()).isEqualTo(RotationStatus.TERMINE);
            assertThat(lot.getStatut()).isEqualTo(LotStatus.TERMINE);
            verify(lotRepository).save(lot);

            // CORRECTION ICI : On vérifie qu'on a bien envoyé DEUX messages sur le topic du lot
            // Le premier pour "EN_COURS" et le second pour "TERMINE"
            verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/lots/10/status"), any(Object.class));
        }

        @Test
        @DisplayName("Verrouille toute notation non encore verrouillée du groupe")
        void validerGroupe_verrouilleNotationsNonVerrouillees() {
            Lot lot = new Lot(); lot.setId(10L);
            Rotation r = rotationWithLot(1L, lot, 1);

            RotationAssignment ra1 = new RotationAssignment(); ra1.setId(1L);
            RotationAssignment ra2 = new RotationAssignment(); ra2.setId(2L);
            Notation n1 = new Notation(); n1.setId(1L); n1.setVerouillee(false);
            Notation n2 = new Notation(); n2.setId(2L); n2.setVerouillee(true); // déjà verrouillée

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(r));
            when(rotationAssignmentRepository.findByRotationId(1L)).thenReturn(List.of(ra1, ra2));
            when(notationRepository.findByAssignmentId(1L)).thenReturn(Optional.of(n1));
            when(notationRepository.findByAssignmentId(2L)).thenReturn(Optional.of(n2));
            when(rotationRepository.countByStudentGroup_Lot_IdAndStatutNot(10L, RotationStatus.TERMINE))
                    .thenReturn(1L);

            service.validerGroupe(1L, EVAL_ID);

            assertThat(n1.getVerouillee()).isTrue();
            verify(notationRepository).save(n1);
            // n2 était déjà verrouillée → pas de save inutile
            verify(notationRepository, never()).save(n2);
        }

        @Test
        @DisplayName("Groupe déjà validé (rotation déjà TERMINE) → BusinessException")
        void validerGroupe_dejaValide() {
            Rotation r = rotationWithLot(1L, new Lot(), 1);
            r.setStatut(RotationStatus.TERMINE);

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.validerGroupe(1L, EVAL_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("déjà validé");
        }

        @Test
        @DisplayName("Rotation d'un autre évaluateur → AccessDeniedException")
        void validerGroupe_horsPerimetre() {
            Rotation r = rotationWithLot(1L, new Lot(), 1);
            r.setEvaluateurId(999L);

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(r));

            assertThatThrownBy(() -> service.validerGroupe(1L, EVAL_ID))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Rotation introuvable → ResourceNotFoundException")
        void validerGroupe_rotationIntrouvable() {
            when(rotationRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.validerGroupe(99L, EVAL_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Rotation sans groupe/lot : la rotation est quand même clôturée, sans exception")
        void validerGroupe_sansLot() {
            Rotation r = new Rotation();
            r.setId(1L); r.setEvaluateurId(EVAL_ID); r.setStatut(RotationStatus.EN_ATTENTE);
            r.setStudentGroup(null);

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(r));
            when(rotationAssignmentRepository.findByRotationId(1L)).thenReturn(List.of());

            assertThatCode(() -> service.validerGroupe(1L, EVAL_ID)).doesNotThrowAnyException();
            assertThat(r.getStatut()).isEqualTo(RotationStatus.TERMINE);
            verify(lotRepository, never()).save(any());
        }
    }

    // =========================================================================
    // validerLot — #211 : cascade NEUTRALISÉE. Recalcul d'oversight (admin) qui
    // DÉRIVE lot.statut de l'état stocké des rotations, sans JAMAIS écrire de
    // statut de rotation (ADR-0014 §4).
    // =========================================================================

    @Nested
    @DisplayName("validerLot()")
    class ValiderLotLogic {

        @Test
        @DisplayName("Toutes rotations TERMINE (restantes=0) → lot TERMINE, aucune rotation écrite")
        void validerLot_deriveTermine() {
            Lot lot = new Lot();
            lot.setId(10L); lot.setEvaluateurId(EVAL_ID);
            lot.setStatut(LotStatus.EN_COURS);

            when(lotRepository.findById(10L)).thenReturn(Optional.of(lot));
            when(rotationRepository.countByStudentGroup_Lot_IdAndStatutNot(10L, RotationStatus.TERMINE))
                    .thenReturn(0L);

            service.validerLot(10L, EVAL_ID);

            assertThat(lot.getStatut()).isEqualTo(LotStatus.TERMINE);
            verify(lotRepository).save(lot);
            // #211 : la neutralisation garantit qu'AUCUNE rotation n'est forcée.
            verify(rotationRepository, never()).save(any(Rotation.class));
        }

        @Test
        @DisplayName("Des rotations restent non terminées → lot EN_COURS, aucune rotation écrite")
        void validerLot_deriveEnCours() {
            Lot lot = new Lot();
            lot.setId(10L); lot.setEvaluateurId(EVAL_ID);
            lot.setStatut(LotStatus.EN_COURS);

            when(lotRepository.findById(10L)).thenReturn(Optional.of(lot));
            when(rotationRepository.countByStudentGroup_Lot_IdAndStatutNot(10L, RotationStatus.TERMINE))
                    .thenReturn(3L);

            service.validerLot(10L, EVAL_ID);

            assertThat(lot.getStatut()).isEqualTo(LotStatus.EN_COURS);
            verify(lotRepository).save(lot);
            verify(rotationRepository, never()).save(any(Rotation.class));
        }

        @Test
        @DisplayName("Diffuse le statut DÉRIVÉ du lot via WebSocket après validation")
        void validerLot_broadcastStatut() {
            Lot lot = new Lot();
            lot.setId(10L); lot.setEvaluateurId(EVAL_ID);
            lot.setStatut(LotStatus.EN_COURS);

            when(lotRepository.findById(10L)).thenReturn(Optional.of(lot));
            when(rotationRepository.countByStudentGroup_Lot_IdAndStatutNot(10L, RotationStatus.TERMINE))
                    .thenReturn(0L);

            service.validerLot(10L, EVAL_ID);

            verify(messagingTemplate).convertAndSend(eq("/topic/lots/10/status"), any(Object.class));
        }

        @Test
        @DisplayName("Lot introuvable → ResourceNotFoundException")
        void validerLot_lotIntrouvable() {
            when(lotRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.validerLot(99L, EVAL_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Lot introuvable");
        }
    }

    /**
     * Filtre par examen (#189) — et surtout le garde-fou qui a manqué.
     */
    @Nested
    @DisplayName("#189/#190 — filtre par examen, sans jamais vider le dashboard")
    class FiltreExamen {

        private Rotation rotationLiee() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).plusHours(2));
            lotFor(r, LotStatus.EN_COURS);
            return r;
        }

        private void timing(String statut) {
            when(examServiceClient.getExamTiming(anyLong()))
                    .thenReturn(new ExamServiceClient.ExamTiming(false, null, 0, 15, 0, statut));
        }

        @Test
        @DisplayName("Examen EN_COURS → ses sessions sont visibles")
        void enCours_visible() {
            Rotation r = rotationLiee();
            timing("EN_COURS");
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));

            assertThat(service.buildDashboard(EVAL_ID).getSessions()).isNotEmpty();
        }

        @Test
        @DisplayName("Examen CONFIGURE (jamais lancé) → exclu")
        void configure_exclu() {
            timing("CONFIGURE");

            assertThat(service.buildDashboard(EVAL_ID).getSessions()).isEmpty();
            verify(rotationRepository, never())
                    .findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList());
        }

        @Test
        @DisplayName("Examen TERMINE (clôturé) → exclu")
        void termine_exclu() {
            timing("TERMINE");

            assertThat(service.buildDashboard(EVAL_ID).getSessions()).isEmpty();
        }

        @Test
        @DisplayName("RÉGRESSION : statut INCONNU (exam-service 403/injoignable) → l'examen est CONSERVÉ, "
                + "le dashboard n'est JAMAIS vidé")
        void statutInconnu_failOpen() {
            Rotation r = rotationLiee();
            timing(null);
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));

            assertThat(service.buildDashboard(EVAL_ID).getSessions())
                    .as("un statut inconnu ne doit jamais faire disparaître les sessions de l'évaluateur")
                    .isNotEmpty();
        }
    }
}
