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
 * Covers every public method and non-trivial private branch:
 *   buildDashboard / resolveSessionStatut / buildSessions / buildPlanning /
 *   getLotDetail / saisirNotation / validerEtudiant / validerLot.
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

    // Vraie horloge (pas un mock) pinnée Africa/Tunis comme ClockConfig, pour que
    // le temps effectif du service s'aligne sur les debutCreneau construits ici.
    @Spy private Clock clock = Clock.system(ZoneId.of("Africa/Tunis"));

    @InjectMocks
    private EvaluateurDashboardService service;

    private static final Long EVAL_ID    = 1L;
    private static final Long STATION_ID = 100L;
    private static final ZoneId TUNIS    = ZoneId.of("Africa/Tunis");

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

        @BeforeEach
        void stubStation() {
            lenient().when(examServiceClient.getStationInfo(STATION_ID))
                    .thenReturn(new ExamServiceClient.StationInfo("Station Test"));
            lenient().when(lotRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of());
        }

        @Test
        @DisplayName("A_VENIR : début dans le futur")
        void statut_aVenir() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).plusHours(2));
            when(rotationRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of(r));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions().get(0).getStatut()).isEqualTo("A_VENIR");
        }

        @Test
        @DisplayName("EN_COURS : dans la fenêtre étendue (début il y a 20 min)")
        void statut_enCours_pendantGrace() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(20));
            when(rotationRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of(r));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions().get(0).getStatut()).isEqualTo("EN_COURS");
        }

        @Test
        @DisplayName("TERMINEE : au-delà de la fenêtre étendue (début il y a 60 min)")
        void statut_terminee_apresGrace() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(60));
            when(rotationRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of(r));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions().get(0).getStatut()).isEqualTo("TERMINEE");
        }

        @Test
        @DisplayName("TERMINEE : rotation explicitement marquée TERMINE (validerLot)")
        void statut_terminee_rotationExplicite() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(10));
            r.setStatut(RotationStatus.TERMINE);
            when(rotationRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of(r));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions().get(0).getStatut()).isEqualTo("TERMINEE");
        }

        @Test
        @DisplayName("TERMINEE : lot associé au statut TERMINE")
        void statut_terminee_lotTermine() {
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(5));
            lotFor(r, LotStatus.TERMINE);   // attache le sg avec lot TERMINE sur la rotation
            when(rotationRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of(r));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions().get(0).getStatut()).isEqualTo("TERMINEE");
        }

        @Test
        @DisplayName("heureFin non nulle uniquement pour les sessions TERMINEE")
        void heureFin_seulementPourTerminee() {
            Rotation rTerminee = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(60));
            Rotation rAVenir   = rotationAt(LocalDateTime.now(TUNIS).plusHours(1));
            rAVenir.setId(2L);
            rAVenir.setStationId(STATION_ID);
            when(rotationRepository.findByEvaluateurId(EVAL_ID))
                    .thenReturn(List.of(rTerminee, rAVenir));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            // sessions triées : EN_COURS avant A_VENIR avant TERMINEE.
            // Ici les deux ont des statuts distincts; trouver TERMINEE par filtre.
            resp.getSessions().stream()
                    .filter(s -> "TERMINEE".equals(s.getStatut()))
                    .forEach(s -> assertThat(s.getHeureFin()).isNotNull());
            resp.getSessions().stream()
                    .filter(s -> !"TERMINEE".equals(s.getStatut()))
                    .forEach(s -> assertThat(s.getHeureFin()).isNull());
        }

        @Test
        @DisplayName("Rotation sans stationId ou sans debutCreneau est ignorée")
        void rotation_sansChampObligatoire_ignoree() {
            Rotation sans = new Rotation();
            sans.setId(99L);
            // pas de stationId ni debutCreneau
            when(rotationRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of(sans));

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
            lenient().when(lotRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of());
        }

        private String statutOf(Rotation r) {
            when(rotationRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of(r));
            return service.buildDashboard(EVAL_ID).getSessions().get(0).getStatut();
        }

        @Test
        @DisplayName("Pause active : l'horloge effective recule → A_VENIR (brut = EN_COURS)")
        void pauseActive_rembobine_versAVenir() {
            // debut il y a 5 min : en heure brute la session serait EN_COURS.
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(5));
            lotFor(r, LotStatus.EN_COURS);

            // En pause depuis 10 min → temps effectif ≈ maintenant − 10 min,
            // soit AVANT le début → A_VENIR.
            when(examServiceClient.getExamTiming(EXAMEN_ID)).thenReturn(
                    new ExamServiceClient.ExamTiming(
                            true, LocalDateTime.now(TUNIS).minusMinutes(10), 0, 15));

            assertThat(statutOf(r)).isEqualTo("A_VENIR");
        }

        @Test
        @DisplayName("Pause cumulée : temps effectif reculé → encore EN_COURS (brut = TERMINEE)")
        void pauseCumulee_maintientEnCours() {
            // debut il y a 50 min : brut → 50 > 15+30 grâce → TERMINEE.
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(50));
            lotFor(r, LotStatus.EN_COURS);

            // 20 min de pause cumulée → effectif ≈ −30 min depuis le début,
            // dans la fenêtre 15+30 → EN_COURS.
            when(examServiceClient.getExamTiming(EXAMEN_ID)).thenReturn(
                    new ExamServiceClient.ExamTiming(false, null, 20 * 60, 15));

            assertThat(statutOf(r)).isEqualTo("EN_COURS");
        }

        @Test
        @DisplayName("Durée lue de la config examen (60) élargit la fenêtre → EN_COURS")
        void dureeDepuisConfig_elargitFenetre() {
            // debut il y a 50 min, sans pause : avec la durée par défaut (15) la
            // session serait TERMINEE (50 > 45). Avec dureeStationMin=60 de la
            // config : fenêtre 60+30=90 → EN_COURS.
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(50));
            lotFor(r, LotStatus.EN_COURS);

            when(examServiceClient.getExamTiming(EXAMEN_ID)).thenReturn(
                    new ExamServiceClient.ExamTiming(false, null, 0, 60));

            assertThat(statutOf(r)).isEqualTo("EN_COURS");
        }

        @Test
        @DisplayName("Timing neutre (exam-service injoignable) : repli sur l'heure brute")
        void timingNeutre_repliSurHeureBrute() {
            // Sans pause ni durée → comportement identique à l'heure murale brute.
            Rotation r = rotationAt(LocalDateTime.now(TUNIS).minusMinutes(20));
            lotFor(r, LotStatus.EN_COURS);

            when(examServiceClient.getExamTiming(EXAMEN_ID))
                    .thenReturn(ExamServiceClient.ExamTiming.neutral());

            assertThat(statutOf(r)).isEqualTo("EN_COURS");
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
            // FIX NPE: buildDashboard appelle buildSessions qui appelle examServiceClient.getStationInfo
            // On le met en lenient car tous les tests de ce bloc n'en ont pas forcément besoin,
            // mais buildDashboard l'appelle systématiquement.
            lenient().when(examServiceClient.getStationInfo(anyLong()))
                    .thenReturn(new ExamServiceClient.StationInfo("Station Test"));
        }

        @Test
        @DisplayName("Stats : totalEtudiants = somme des tailleLot, lotsValides = lots TERMINE")
        void stats_agregation() {
            Lot l1 = new Lot(); l1.setId(1L); l1.setExamenId(1L); l1.setTailleLot(10);
            l1.setStatut(LotStatus.TERMINE);
            Lot l2 = new Lot(); l2.setId(2L); l2.setExamenId(1L); l2.setTailleLot(8);
            l2.setStatut(LotStatus.EN_ATTENTE);

            when(rotationRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of());
            when(lotRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of(l1, l2));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getStats().getTotalEtudiants()).isEqualTo(18);
            assertThat(resp.getStats().getLotsValides()).isEqualTo(1);
            assertThat(resp.getStats().getTotalLots()).isEqualTo(2);
        }

        @Test
        @DisplayName("Planning : filtre sur la date du jour, trié par heure")
        void planning_filtreSurJourJ() {
            LocalDateTime maintenant = LocalDateTime.now(TUNIS);

            Rotation rAujourdHui  = rotationAt(maintenant.withHour(9).withMinute(0));
            rAujourdHui.setStatut(RotationStatus.EN_ATTENTE);

            Rotation rDemain = rotationAt(maintenant.plusDays(1).withHour(9).withMinute(0));
            rDemain.setId(2L); rDemain.setStationId(STATION_ID);

            when(rotationRepository.findByEvaluateurId(EVAL_ID))
                    .thenReturn(List.of(rAujourdHui, rDemain));
            when(lotRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of());

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getPlanning()).hasSize(1);
        }

        @Test
        @DisplayName("Planning statut TERMINE si rotation marquée TERMINE")
        void planning_statutTermine_siRotationTermine() {
            LocalDateTime maintenant = LocalDateTime.now(TUNIS);
            Rotation r = rotationAt(maintenant.withHour(8).withMinute(0));
            r.setStatut(RotationStatus.TERMINE);

            when(rotationRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of(r));
            when(lotRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of());

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getPlanning().get(0).getStatut()).isEqualTo("TERMINE");
        }

        @Test
        @DisplayName("Lot sans tailleLot (null) compte pour 0 dans totalEtudiants")
        void stats_tailleLotNull() {
            Lot lot = new Lot(); lot.setId(1L); lot.setExamenId(1L);
            lot.setStatut(LotStatus.EN_ATTENTE);
            // tailleLot = null intentionnellement

            when(rotationRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of());
            when(lotRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of(lot));

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

            ExamenParticipation p = participation(1L);
            p.setLot(lot);

            when(lotRepository.findByEvaluateurIdAndNumeroLot(EVAL_ID, 2)).thenReturn(Optional.of(lot));
            when(lotRepository.countByExamenId(99L)).thenReturn(3);
            when(participationRepository.findByLotId(10L)).thenReturn(List.of(p));
            when(rotationAssignmentRepository.findByParticipationId(1L)).thenReturn(Optional.empty());

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

            when(lotRepository.findByEvaluateurIdAndNumeroLot(EVAL_ID, 1)).thenReturn(Optional.of(lot));
            when(lotRepository.countByExamenId(1L)).thenReturn(1);
            when(participationRepository.findByLotId(5L)).thenReturn(List.of());

            LotDetailResponse resp = service.getLotDetail(STATION_ID, 1, EVAL_ID);

            assertThat(resp.isValide()).isTrue();
        }

        @Test
        @DisplayName("Participation sans étudiant est filtrée")
        void lotDetail_participationSansEtudiant() {
            Lot lot = new Lot();
            lot.setId(5L); lot.setExamenId(1L); lot.setNumeroLot(1);
            lot.setStatut(LotStatus.EN_ATTENTE);

            ExamenParticipation orphan = new ExamenParticipation();
            orphan.setId(99L);
            orphan.setEtudiant(null);    // pas d'étudiant lié

            when(lotRepository.findByEvaluateurIdAndNumeroLot(EVAL_ID, 1)).thenReturn(Optional.of(lot));
            when(lotRepository.countByExamenId(1L)).thenReturn(1);
            when(participationRepository.findByLotId(5L)).thenReturn(List.of(orphan));

            LotDetailResponse resp = service.getLotDetail(STATION_ID, 1, EVAL_ID);

            assertThat(resp.getEtudiants()).isEmpty();
        }

        @Test
        @DisplayName("Notation verrouillée → verrouille = true dans la réponse")
        void lotDetail_notationVerrouillee() {
            Lot lot = new Lot();
            lot.setId(5L); lot.setExamenId(1L); lot.setNumeroLot(1);
            lot.setStatut(LotStatus.EN_ATTENTE);

            ExamenParticipation p = participation(7L);
            p.setLot(lot);

            RotationAssignment ra = new RotationAssignment(); ra.setId(20L);
            Notation n = new Notation(); n.setId(30L); n.setVerouillee(true);

            when(lotRepository.findByEvaluateurIdAndNumeroLot(EVAL_ID, 1)).thenReturn(Optional.of(lot));
            when(lotRepository.countByExamenId(1L)).thenReturn(1);
            when(participationRepository.findByLotId(5L)).thenReturn(List.of(p));
            when(rotationAssignmentRepository.findByParticipationId(7L)).thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(20L)).thenReturn(Optional.of(n));
            when(notationItemRepository.findByNotationId(30L)).thenReturn(List.of());

            LotDetailResponse resp = service.getLotDetail(STATION_ID, 1, EVAL_ID);

            assertThat(resp.getEtudiants().get(0).isVerrouille()).isTrue();
        }

        @Test
        @DisplayName("Lot introuvable → ResourceNotFoundException")
        void lotDetail_lotIntrouvable() {
            when(lotRepository.findByEvaluateurIdAndNumeroLot(EVAL_ID, 99))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getLotDetail(STATION_ID, 99, EVAL_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("introuvable");
        }

        @Test
        @DisplayName("num_echantillon non numérique → numeroEchantillon = null")
        void lotDetail_echantillonNonNumerique() {
            Lot lot = new Lot();
            lot.setId(5L); lot.setExamenId(1L); lot.setNumeroLot(1);
            lot.setStatut(LotStatus.EN_ATTENTE);

            ExamenParticipation p = participation(3L);
            p.setNum_echantillon("ABC");  // non numérique
            p.setLot(lot);

            when(lotRepository.findByEvaluateurIdAndNumeroLot(EVAL_ID, 1)).thenReturn(Optional.of(lot));
            when(lotRepository.countByExamenId(1L)).thenReturn(1);
            when(participationRepository.findByLotId(5L)).thenReturn(List.of(p));
            when(rotationAssignmentRepository.findByParticipationId(3L)).thenReturn(Optional.empty());

            LotDetailResponse resp = service.getLotDetail(STATION_ID, 1, EVAL_ID);

            assertThat(resp.getEtudiants().get(0).getNumeroEchantillon()).isNull();
        }
    }

    // =========================================================================
    // saisirNotation
    // =========================================================================

    @Nested
    @DisplayName("saisirNotation()")
    class SaisirNotation {

        @Test
        @DisplayName("Participation introuvable → ResourceNotFoundException")
        void participationIntrouvable() {
            SaisirNotationRequest req = new SaisirNotationRequest(1L, STATION_ID, 1L, 1L, 10f);
            when(participationRepository.findByEtudiantIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.saisirNotation(req, EVAL_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Participation introuvable");
        }

        @Test
        @DisplayName("Notation verrouillée → BusinessException")
        void notationVerrouillee() {
            ExamenParticipation p = participation(1L); p.setId(100L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(200L);
            Notation n = new Notation(); n.setId(1L); n.setGrilleId(1L); n.setVerouillee(true);

            when(participationRepository.findByEtudiantIdAndStationId(1L, STATION_ID))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationId(100L))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(200L)).thenReturn(Optional.of(n));

            SaisirNotationRequest req = new SaisirNotationRequest(1L, STATION_ID, 1L, 5L, 1.0f);

            assertThatThrownBy(() -> service.saisirNotation(req, EVAL_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("verrouillée");
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
            when(rotationAssignmentRepository.findByParticipationId(100L))
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
            when(rotationAssignmentRepository.findByParticipationId(100L))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(200L)).thenReturn(Optional.of(n));
            when(notationItemRepository.findByNotationIdAndItemId(1L, 7L))
                    .thenReturn(Optional.empty());

            // On utilise lenient() ici car selon si la liste est vide ou non,
            // les appels suivants peuvent être ignorés par le compilateur JIT ou la logique métier.
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
            when(rotationAssignmentRepository.findByParticipationId(100L))
                    .thenReturn(Optional.empty());
            when(rotationRepository.findFirstByEvaluateurIdAndStationIdOrderByIdDesc(EVAL_ID, STATION_ID))
                    .thenReturn(Optional.of(rotation));

            // Utilisation de lenient() pour les sauvegardes et les calculs de score
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
            when(rotationAssignmentRepository.findByParticipationId(100L))
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
            when(rotationAssignmentRepository.findByParticipationId(100L))
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
            when(rotationAssignmentRepository.findByParticipationId(100L))
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
            when(rotationAssignmentRepository.findByParticipationId(100L))
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
            when(rotationAssignmentRepository.findByParticipationId(1L))
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
        @DisplayName("Présent avec commentaire : commentaire persisté")
        void validerEtudiant_present_avecCommentaire() {
            ExamenParticipation p = participation(1L); p.setId(1L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(55L);
            Notation n = new Notation(); n.setId(10L); n.setScore_final(12f);

            when(participationRepository.findByEtudiantIdAndStationId(anyLong(), anyLong()))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationId(1L))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(55L)).thenReturn(Optional.of(n));

            ValiderEtudiantRequest req = new ValiderEtudiantRequest();
            req.setAbsent(false);
            req.setCommentaire("Bon travail");
            req.setGrilleId(1L);
            service.validerEtudiant(1L, STATION_ID, EVAL_ID, req);

            assertThat(p.getCommentaire()).isEqualTo("Bon travail");
            assertThat(p.getEst_present()).isTrue();
            assertThat(p.getNote()).isEqualTo(12f);
        }

        @Test
        @DisplayName("Participation introuvable → ResourceNotFoundException")
        void validerEtudiant_participationIntrouvable() {
            when(participationRepository.findByEtudiantIdAndStationId(99L, STATION_ID))
                    .thenReturn(Optional.empty());

            ValiderEtudiantRequest req = new ValiderEtudiantRequest();
            req.setGrilleId(1L);

            assertThatThrownBy(() -> service.validerEtudiant(99L, STATION_ID, EVAL_ID, req))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Participation introuvable");
        }

        @Test
        @DisplayName("Absent sans items existants : deleteAll non appelé")
        void validerEtudiant_absent_sansItems() {
            ExamenParticipation p = participation(1L); p.setId(1L);
            RotationAssignment ra = new RotationAssignment(); ra.setId(55L);
            Notation n = new Notation(); n.setId(10L);

            when(participationRepository.findByEtudiantIdAndStationId(anyLong(), anyLong()))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationId(1L))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(55L)).thenReturn(Optional.of(n));
            when(notationItemRepository.findByNotationId(10L)).thenReturn(List.of());

            ValiderEtudiantRequest req = new ValiderEtudiantRequest();
            req.setAbsent(true); req.setGrilleId(1L);
            service.validerEtudiant(1L, STATION_ID, EVAL_ID, req);

            verify(notationItemRepository, never()).deleteAll(anyList());
        }

        @Test
        @DisplayName("Commentaire vide ou blank n'écrase pas le commentaire existant")
        void validerEtudiant_commentaireBlank() {
            ExamenParticipation p = participation(1L); p.setId(1L);
            p.setCommentaire("ancien");
            RotationAssignment ra = new RotationAssignment(); ra.setId(55L);
            Notation n = new Notation(); n.setId(10L); n.setScore_final(5f);

            when(participationRepository.findByEtudiantIdAndStationId(anyLong(), anyLong()))
                    .thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationId(1L))
                    .thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(55L)).thenReturn(Optional.of(n));

            ValiderEtudiantRequest req = new ValiderEtudiantRequest();
            req.setAbsent(false); req.setCommentaire("   "); req.setGrilleId(1L);
            service.validerEtudiant(1L, STATION_ID, EVAL_ID, req);

            assertThat(p.getCommentaire()).isEqualTo("ancien");
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
        @DisplayName("Rotation déjà TERMINE : pas de double save")
        void validerLot_rotationDejaTermine() {
            Lot lot = new Lot();
            lot.setId(10L); lot.setEvaluateurId(EVAL_ID);

            StudentGroup sg = new StudentGroup();
            Rotation r = new Rotation(); r.setStatut(RotationStatus.TERMINE);
            sg.setRotations(List.of(r));
            lot.setGroups(List.of(sg));

            when(lotRepository.findById(10L)).thenReturn(Optional.of(lot));

            service.validerLot(10L, EVAL_ID);

            verify(rotationRepository, never()).save(r);
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
        @DisplayName("Accès refusé si le lot appartient à un autre évaluateur")
        void validerLot_forbidden() {
            Lot lot = new Lot();
            lot.setId(10L); lot.setEvaluateurId(999L);
            when(lotRepository.findById(10L)).thenReturn(Optional.of(lot));

            assertThatThrownBy(() -> service.validerLot(10L, EVAL_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Accès refusé");
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
}