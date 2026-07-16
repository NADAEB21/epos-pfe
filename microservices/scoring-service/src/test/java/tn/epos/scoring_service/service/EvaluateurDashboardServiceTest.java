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
 *   getLotDetail / saisirNotation / validerEtudiant / validerLot.
 *
 * NB: aligné sur la nouvelle logique du service (lots dérivés des rotations,
 * plus de vérification d'accès en service, plus de gestion du commentaire
 * dans validerEtudiant, avertissementLeadSec non propagé, etc.)
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
    @Mock private ExamServiceClient              examServiceClient;
    @Mock private SimpMessagingTemplate          messagingTemplate;

    // Vraie horloge (pas un mock) pinnée Africa/Tunis comme ClockConfig, pour que
    // le temps effectif du service s'aligne sur les debutCreneau construits ici.
    @Spy private Clock clock = Clock.system(ZoneId.of("Africa/Tunis"));

    @InjectMocks
    private EvaluateurDashboardService service;

    private static final Long EVAL_ID    = 1L;
    private static final Long STATION_ID = 100L;
    private static final ZoneId TUNIS    = ZoneId.of("Africa/Tunis");

    @BeforeEach
    void globalSetup() {
        // Mock pour que le service trouve un examen
        lenient().when(rotationRepository.findDistinctExamenIdsByEvaluateurId(EVAL_ID))
                .thenReturn(List.of(99L));

        // Mock pour que l'examen soit considéré comme EN_COURS
        lenient().when(examServiceClient.getExamTiming(anyLong()))
                .thenReturn(new ExamServiceClient.ExamTiming(false, null, 0, 15, 0, "EN_COURS"));

        // Mock pour les infos de station
        lenient().when(examServiceClient.getStationInfo(anyLong()))
                .thenReturn(new ExamServiceClient.StationInfo("Station Test"));
    }

    // ─── shared helpers ──────────────────────────────────────────────────────

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
        sg.setLot(lot);
        sg.setRotations(List.of(r));
        lot.setGroups(List.of(sg));
        r.setStudentGroup(sg);
        return lot;
    }

    /**
     * Relie une rotation minimale (sans créneau) à un lot donné, via un
     * StudentGroup — reproduit exactement ce que lit
     * resolverLotsDepuisRotations() / getLotDetail() côté service.
     */
    private Rotation rotationWithLot(Long rotationId, Lot lot) {
        Rotation r = new Rotation();
        r.setId(rotationId);
        r.setStationId(STATION_ID);
        StudentGroup sg = new StudentGroup();
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
        @DisplayName("EN_COURS : dans la fenêtre étendue (début il y a 20 min)")
        void statut_enCours_pendantGrace() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(20));
            lotFor(r, LotStatus.EN_COURS);
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions().get(0).getStatut()).isEqualTo("EN_COURS");
        }

        @Test
        @DisplayName("TERMINEE : au-delà de la fenêtre étendue (début il y a 60 min)")
        void statut_terminee_apresGrace() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(60));
            lotFor(r, LotStatus.EN_COURS);
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions().get(0).getStatut()).isEqualTo("TERMINEE");
        }

        @Test
        @DisplayName("TERMINEE : rotation explicitement marquée TERMINE (validerLot)")
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
            // Rotation active, démarrée il y a 5 min → dans la fenêtre (15+30) → EN_COURS,
            // même si le lot lié est déjà marqué TERMINE : resolveSessionStatut() ne lit
            // plus que rotation.getStatut() et le temps.
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(5));
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

            assertThat(resp.getSessions()).isNotEmpty(); // garde-fou contre un forEach silencieux
            resp.getSessions().forEach(s -> assertThat(s.getHeureFin()).isNotNull());
        }

        @Test
        @DisplayName("Rotation sans stationId ou sans debutCreneau est ignorée")
        void rotation_sansChampObligatoire_ignoree() {
            Rotation sans = new Rotation();
            sans.setId(99L);
            // pas de stationId ni debutCreneau
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(sans));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions()).isEmpty();
        }
    }

    // =========================================================================
    // buildDashboard — temps effectif / pause (ADR-0012 §0)
    // =========================================================================

    @Nested
    @DisplayName("buildDashboard() — réconciliation du temps effectif (pause)")
    class EffectiveTimePauseReconciliation {

        private static final Long EXAMEN_ID = 99L;  // celui que pose lotFor(...)

        @BeforeEach
        void stubCommon() {
            lenient().when(examServiceClient.getStationInfo(STATION_ID))
                    .thenReturn(new ExamServiceClient.StationInfo("Station Test"));
        }

        private String statutOf(Rotation r) {
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));
            return service.buildDashboard(EVAL_ID).getSessions().get(0).getStatut();
        }

        @Test
        @DisplayName("Pause active : l'horloge effective recule → A_VENIR (brut = EN_COURS)")
        void pauseActive_rembobine_versAVenir() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(5));
            lotFor(r, LotStatus.EN_COURS);

            when(examServiceClient.getExamTiming(EXAMEN_ID)).thenReturn(
                    new ExamServiceClient.ExamTiming(
                            true, LocalDateTime.now(TUNIS).minusMinutes(10), 0, 15, 0, "EN_COURS"));

            assertThat(statutOf(r)).isEqualTo("A_VENIR");
        }

        @Test
        @DisplayName("Pause cumulée : temps effectif reculé → encore EN_COURS (brut = TERMINEE)")
        void pauseCumulee_maintientEnCours() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(50));
            lotFor(r, LotStatus.EN_COURS);

            when(examServiceClient.getExamTiming(EXAMEN_ID)).thenReturn(
                    new ExamServiceClient.ExamTiming(false, null, 20 * 60, 15, 0, "EN_COURS"));

            assertThat(statutOf(r)).isEqualTo("EN_COURS");
        }

        @Test
        @DisplayName("Durée lue de la config examen (60) élargit la fenêtre → EN_COURS")
        void dureeDepuisConfig_elargitFenetre() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(50));
            lotFor(r, LotStatus.EN_COURS);

            when(examServiceClient.getExamTiming(EXAMEN_ID)).thenReturn(
                    new ExamServiceClient.ExamTiming(false, null, 0, 60, 0, "EN_COURS"));

            assertThat(statutOf(r)).isEqualTo("EN_COURS");
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

        private static final Long EXAMEN_ID = 99L;  // celui que pose lotFor(...)

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

            // Le service ne mappe plus avertissementLeadSec dans SessionResponse.
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
        @DisplayName("Stats : lots dérivés des rotations (resolverLotsDepuisRotations) — totalEtudiants, lotsValides")
        void stats_agregation() {
            Lot l1 = new Lot(); l1.setId(1L); l1.setExamenId(1L); l1.setNumeroLot(1);
            l1.setTailleLot(10); l1.setStatut(LotStatus.TERMINE);
            Lot l2 = new Lot(); l2.setId(2L); l2.setExamenId(1L); l2.setNumeroLot(2);
            l2.setTailleLot(8); l2.setStatut(LotStatus.EN_ATTENTE);

            Rotation r1 = rotationWithLot(1L, l1);
            Rotation r2 = rotationWithLot(2L, l2);

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
            // tailleLot = null intentionnellement

            Rotation r = rotationWithLot(1L, lot);
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getStats().getTotalEtudiants()).isZero();
        }
    }

    // =========================================================================
    // getLotDetail
    // =========================================================================

    @Nested
    @DisplayName("getLotDetail()")
    class GetLotDetail {

        @Test
        @DisplayName("200 — retourne le lot avec ses étudiants et leur statut verrouillé")
        void lotDetail_happy() {
            Lot lot = new Lot();
            lot.setId(10L); lot.setExamenId(99L); lot.setNumeroLot(2);
            lot.setTailleLot(2); lot.setStatut(LotStatus.EN_COURS);
            Rotation r = rotationWithLot(1L, lot);

            ExamenParticipation p = participation(1L);

            RotationAssignment ra = new RotationAssignment();
            ra.setId(100L); ra.setParticipation(p); ra.setRotation(r);

            when(rotationRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of(r));
            when(lotRepository.countByExamenId(99L)).thenReturn(3);
            // when(participationRepository.findByLotId(10L)).thenReturn(List.of(p));
            when(rotationAssignmentRepository.findByRotationId(1L)).thenReturn(List.of(ra));

            LotDetailResponse resp = service.getLotDetail(STATION_ID, 2, EVAL_ID);

            assertThat(resp.getId()).isEqualTo(10L);
            assertThat(resp.getNumero()).isEqualTo(2);
            assertThat(resp.getTotal()).isEqualTo(3);
            assertThat(resp.isValide()).isFalse();
            assertThat(resp.getEtudiants()).hasSize(1);
            assertThat(resp.getEtudiants().get(0).getNom()).isEqualTo("Nom1");
        }

        @Test
        @DisplayName("Lot TERMINE → valide = true")
        void lotDetail_valide() {
            Lot lot = new Lot();
            lot.setId(5L); lot.setExamenId(1L); lot.setNumeroLot(1);
            lot.setStatut(LotStatus.TERMINE);
            Rotation r = rotationWithLot(1L, lot);

            when(rotationRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of(r));
            when(lotRepository.countByExamenId(1L)).thenReturn(1);
            //when(participationRepository.findByLotId(5L)).thenReturn(List.of());
            when(rotationAssignmentRepository.findByRotationId(1L)).thenReturn(List.of());
            LotDetailResponse resp = service.getLotDetail(STATION_ID, 1, EVAL_ID);

            assertThat(resp.isValide()).isTrue();
        }

        @Test
        @DisplayName("Participation sans étudiant est filtrée")
        void lotDetail_participationSansEtudiant() {
            Lot lot = new Lot();
            lot.setId(5L); lot.setExamenId(1L); lot.setNumeroLot(1);
            lot.setStatut(LotStatus.EN_ATTENTE);
            Rotation r = rotationWithLot(1L, lot);

            ExamenParticipation orphan = new ExamenParticipation();
            orphan.setId(99L);
            orphan.setEtudiant(null);    // pas d'étudiant lié

            RotationAssignment ra = new RotationAssignment();
            ra.setParticipation(orphan);

            when(rotationRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of(r));
            when(lotRepository.countByExamenId(1L)).thenReturn(1);
            when(rotationAssignmentRepository.findByRotationId(1L)).thenReturn(List.of(ra));

            LotDetailResponse resp = service.getLotDetail(STATION_ID, 1, EVAL_ID);

            assertThat(resp.getEtudiants()).isEmpty();
        }

        @Test
        @DisplayName("Notation verrouillée → verrouille = true dans la réponse")
        void lotDetail_notationVerrouillee() {
            Lot lot = new Lot();
            lot.setId(5L); lot.setExamenId(1L); lot.setNumeroLot(1);
            lot.setStatut(LotStatus.EN_ATTENTE);
            Rotation r = rotationWithLot(1L, lot);

            ExamenParticipation p = participation(7L);

            RotationAssignment ra = new RotationAssignment();
            ra.setId(20L);
            ra.setParticipation(p);
            ra.setRotation(r);

            Notation n = new Notation();
            n.setId(30L);
            n.setVerouillee(true);

            when(rotationRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of(r));
            when(lotRepository.countByExamenId(1L)).thenReturn(1);
            // when(participationRepository.findByLotId(5L)).thenReturn(List.of(p));
            when(rotationAssignmentRepository.findByRotationId(1L)).thenReturn(List.of(ra));
            when(notationRepository.findByAssignmentId(20L)).thenReturn(Optional.of(n));
            lenient().when(notationItemRepository.findByNotationId(30L)).thenReturn(List.of());

            LotDetailResponse resp = service.getLotDetail(STATION_ID, 1, EVAL_ID);

            assertThat(resp.getEtudiants().get(0).isVerrouille()).isTrue();
        }

        @Test
        @DisplayName("Lot introuvable → ResourceNotFoundException")
        void lotDetail_lotIntrouvable() {
            when(rotationRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> service.getLotDetail(STATION_ID, 99, EVAL_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("introuvable");
        }

        @Test
        @DisplayName("Plusieurs rotations : seule celle correspondant au numéroLot est retenue")
        void lotDetail_plusieursRotations_filtreCorrect() {
            Lot lotCible = new Lot();
            lotCible.setId(7L); lotCible.setExamenId(2L); lotCible.setNumeroLot(3);
            lotCible.setStatut(LotStatus.EN_ATTENTE);
            Lot autreLot = new Lot();
            autreLot.setId(8L); autreLot.setExamenId(2L); autreLot.setNumeroLot(4);
            autreLot.setStatut(LotStatus.EN_ATTENTE);

            Rotation rAutre = rotationWithLot(1L, autreLot);
            Rotation rCible = rotationWithLot(2L, lotCible);

            when(rotationRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of(rAutre, rCible));
            when(lotRepository.countByExamenId(2L)).thenReturn(5);
            //when(participationRepository.findByLotId(7L)).thenReturn(List.of());
            when(rotationAssignmentRepository.findByRotationId(2L)).thenReturn(List.of());

            LotDetailResponse resp = service.getLotDetail(STATION_ID, 3, EVAL_ID);

            assertThat(resp.getId()).isEqualTo(7L);
            assertThat(resp.getNumero()).isEqualTo(3);
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
            // findByEtudiantId non stubbé → Mockito renvoie une liste vide par défaut → repli échoue aussi

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
            when(examServiceClient.getItemInfosForGrille(1L)).thenReturn(Map.of());

            service.saisirNotation(new SaisirNotationRequest(2L, STATION_ID, 1L, 5L, 1.0f), EVAL_ID);

            verify(notationItemRepository).save(any(NotationItem.class));
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
            when(examServiceClient.getItemInfosForGrille(1L)).thenReturn(Map.of());

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
            lenient().when(examServiceClient.getItemInfosForGrille(1L)).thenReturn(Map.of());

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
            when(examServiceClient.getItemInfosForGrille(1L))
                    .thenReturn(Map.of(5L, new ExamServiceClient.ItemInfo(5L, 4.0, "BINAIRE")));

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
            when(examServiceClient.getItemInfosForGrille(1L))
                    .thenReturn(Map.of(5L, new ExamServiceClient.ItemInfo(5L, 10.0, "NUMERIQUE")));

            service.saisirNotation(new SaisirNotationRequest(1L, STATION_ID, 1L, 5L, 3.5f), EVAL_ID);

            assertThat(n.getScore_final()).isEqualTo(3.5f);
        }

        @Test
        @DisplayName("Score fallback (exam-service indisponible) : somme des valeurs brutes")
        void scoreCalcul_fallback() {
            ExamenParticipation p = participation(1L); p.setId(100L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(200L);
            Notation n = new Notation(); n.setId(1L); n.setGrilleId(1L); n.setVerouillee(false);
            NotationItem ni = new NotationItem();
            ni.setItemId(5L); ni.setValeur(2.5f); ni.setNotation(n);

            when(participationRepository.findByEtudiantIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationIdAndStationId(100L, STATION_ID))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(200L)).thenReturn(Optional.of(n));
            when(notationItemRepository.findByNotationIdAndItemId(1L, 5L))
                    .thenReturn(Optional.of(ni));
            when(notationItemRepository.findByNotationId(1L)).thenReturn(List.of(ni));
            when(examServiceClient.getItemInfosForGrille(1L)).thenReturn(Map.of()); // fallback

            service.saisirNotation(new SaisirNotationRequest(1L, STATION_ID, 1L, 5L, 2.5f), EVAL_ID);

            assertThat(n.getScore_final()).isEqualTo(2.5f);
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

            service.saisirNotation(new SaisirNotationRequest(1L, STATION_ID, 1L, 5L, 1.0f), EVAL_ID);

            assertThat(n.getScore_final()).isEqualTo(0.0f);
        }
    }

    // =========================================================================
    // validerEtudiant
    // =========================================================================

    @Nested
    @DisplayName("validerEtudiant()")
    class ValiderEtudiantLogic {

        @Test
        @DisplayName("Absent : score = 0, notation verrouillée, présence = false")
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
            assertThat(p.getEst_present()).isFalse();
            verify(notationItemRepository).deleteAll(anyList());
        }

        @Test
        @DisplayName("Présent : notation verrouillée, présence confirmée, note = score de la notation")
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
            assertThat(p.getEst_present()).isTrue();
            assertThat(p.getNote()).isEqualTo(12f);
            verify(participationRepository).save(p);
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

            // Le service ne garde pas contre une liste vide : deleteAll(List.of()) est
            // bien appelé, il n'y a pas de branchement isEmpty() dans validerEtudiant().
            verify(notationItemRepository).deleteAll(List.of());
            assertThat(n.getScore_final()).isEqualTo(0.0f);
        }
    }

    // =========================================================================
    // validerLot
    // =========================================================================

    @Nested
    @DisplayName("validerLot()")
    class ValiderLotLogic {

        @Test
        @DisplayName("Cascade : lot ET rotations passent à TERMINE")
        void validerLot_cascadeStatut() {
            Lot lot = new Lot();
            lot.setId(10L); lot.setEvaluateurId(EVAL_ID);

            StudentGroup sg = new StudentGroup();
            Rotation r = new Rotation(); r.setStatut(RotationStatus.EN_ATTENTE);
            sg.setRotations(List.of(r));
            lot.setGroups(List.of(sg));

            when(lotRepository.findById(10L)).thenReturn(Optional.of(lot));

            service.validerLot(10L, EVAL_ID);

            assertThat(lot.getStatut()).isEqualTo(LotStatus.TERMINE);
            assertThat(r.getStatut()).isEqualTo(RotationStatus.TERMINE);
            verify(lotRepository).save(lot);
            verify(rotationRepository).save(r);
        }

        @Test
        @DisplayName("Rotation déjà TERMINE : toujours sauvegardée (pas de garde spécifique)")
        void validerLot_rotationDejaTermine() {
            Lot lot = new Lot();
            lot.setId(10L); lot.setEvaluateurId(EVAL_ID);

            StudentGroup sg = new StudentGroup();
            Rotation r = new Rotation(); r.setStatut(RotationStatus.TERMINE);
            sg.setRotations(List.of(r));
            lot.setGroups(List.of(sg));

            when(lotRepository.findById(10L)).thenReturn(Optional.of(lot));

            service.validerLot(10L, EVAL_ID);

            assertThat(r.getStatut()).isEqualTo(RotationStatus.TERMINE);
            verify(rotationRepository).save(r);
        }

        @Test
        @DisplayName("Lot sans groupes : pas d'exception")
        void validerLot_sansGroupes() {
            Lot lot = new Lot();
            lot.setId(10L); lot.setEvaluateurId(EVAL_ID);
            lot.setGroups(null);

            when(lotRepository.findById(10L)).thenReturn(Optional.of(lot));

            assertThatCode(() -> service.validerLot(10L, EVAL_ID)).doesNotThrowAnyException();
            assertThat(lot.getStatut()).isEqualTo(LotStatus.TERMINE);
        }

        @Test
        @DisplayName("Diffuse le nouveau statut du lot via WebSocket après validation")
        void validerLot_broadcastStatut() {
            Lot lot = new Lot();
            lot.setId(10L); lot.setEvaluateurId(EVAL_ID);
            lot.setGroups(null);

            when(lotRepository.findById(10L)).thenReturn(Optional.of(lot));

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
     *
     * <p>Le filtre ne garde que les examens EN_COURS. Il s'appuie sur le statut renvoyé par
     * exam-service — mais {@code /api/examens/{id}} était INTERDIT à l'évaluateur (403). Le
     * repli renvoyait {@code statut = null}, le {@code equals("EN_COURS")} strict éliminait
     * alors TOUS les examens, et le dashboard de l'évaluateur devenait VIDE le jour J.
     *
     * <p>Deux verrous désormais : l'appel passe par {@code /timing} (lisible par l'évaluateur),
     * et un statut INCONNU ne masque plus jamais un examen (fail open). Montrer un examen de
     * trop est gênant ; n'en montrer aucun est fatal.
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
            timing(null); // exactement ce que renvoyait ExamTiming.neutral() sur un 403
            when(rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(eq(EVAL_ID), anyList()))
                    .thenReturn(List.of(r));

            assertThat(service.buildDashboard(EVAL_ID).getSessions())
                    .as("un statut inconnu ne doit jamais faire disparaître les sessions de l'évaluateur")
                    .isNotEmpty();
        }
    }
}