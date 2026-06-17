package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.dto.dashboard.*;
import tn.epos.scoring_service.entities.*;
import tn.epos.scoring_service.repositories.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EvaluateurDashboardService - Tests complets")
class EvaluateurDashboardServiceTest {

    @Mock private ILotRepository lotRepository;
    @Mock private IRotationRepository rotationRepository;
    @Mock private IRotationAssignmentRepository rotationAssignmentRepository;
    @Mock private INotationRepository notationRepository;
    @Mock private INotationItemRepository notationItemRepository;
    @Mock private IExamenParticipationRepository participationRepository;
    @Mock private ExamServiceClient examServiceClient;

    @InjectMocks
    private EvaluateurDashboardService service;

    private final Long EVAL_ID = 1L;
    private final Long STATION_ID = 100L;

    @Nested
    @DisplayName("buildDashboard()")
    class DashboardLogic {
        private final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");

        @Test
        @DisplayName("Doit calculer le statut EN_COURS si dans la période de grâce")
        void resolveStatut_EnCours_PendantGrace() {
            LocalDateTime maintenantTunis = LocalDateTime.now(TUNIS_ZONE);
            Rotation r = new Rotation();
            r.setStationId(STATION_ID);
            // Debut il y a 20 min (Durée 15 + Grace 30 = 45 min total) -> Toujours EN_COURS
            r.setDebutCreneau(maintenantTunis.minusMinutes(20));
            r.setStatut(RotationStatus.EN_ATTENTE);

            when(rotationRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of(r));
            when(examServiceClient.getStationInfo(STATION_ID))
                    .thenReturn(new ExamServiceClient.StationInfo( "Station Test"));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions().get(0).getStatut()).isEqualTo("EN_COURS");
        }

        @Test
        @DisplayName("Doit calculer le statut TERMINEE si après la période de grâce")
        void resolveStatut_Terminee_ApresGrace() {
            LocalDateTime maintenantTunis = LocalDateTime.now(TUNIS_ZONE);
            Rotation r = new Rotation();
            r.setStationId(STATION_ID);
            // Debut il y a 60 min -> TERMINEE automatique
            r.setDebutCreneau(maintenantTunis.minusMinutes(60));

            when(rotationRepository.findByEvaluateurId(EVAL_ID)).thenReturn(List.of(r));
            when(examServiceClient.getStationInfo(STATION_ID))
                    .thenReturn(new ExamServiceClient.StationInfo( "Station Test"));

            EvaluateurDashboardResponse resp = service.buildDashboard(EVAL_ID);

            assertThat(resp.getSessions().get(0).getStatut()).isEqualTo("TERMINEE");
        }
    }

    @Nested
    @DisplayName("saisirNotation()")
    class NotationLogic {

        @Test
        @DisplayName("FIX 1 - Doit lever une exception si la participation est introuvable")
        void saisirNotation_ParticipationNotFound() {
            SaisirNotationRequest req = new SaisirNotationRequest(1L, STATION_ID, 1L, 1L, 10f);
            when(participationRepository.findByEtudiantIdAndStationId(1L, STATION_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.saisirNotation(req, EVAL_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Participation introuvable");
        }

        @Test
        @DisplayName("Score - Doit multiplier par la pondération si type BINAIRE")
        void recalculerScore_Binaire() {
            ExamenParticipation p = new ExamenParticipation();
            p.setId(100L);

            RotationAssignment ra = new RotationAssignment();
            ra.setId(200L);

            Notation n = new Notation();
            n.setId(1L);
            n.setGrilleId(1L);

            NotationItem ni = new NotationItem();
            ni.setItemId(5L);
            ni.setValeur(1.0f); // Réussi
            ni.setNotation(n);

            // Mocking de toute la chaîne de saisie (FIX)
            when(participationRepository.findByEtudiantIdAndStationId(1L, STATION_ID)).thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationId(100L)).thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(200L)).thenReturn(Optional.of(n));
            when(notationItemRepository.findByNotationIdAndItemId(1L, 5L)).thenReturn(Optional.of(ni));

            // Mocking pour le calcul du score
            when(notationItemRepository.findByNotationId(1L)).thenReturn(List.of(ni));
            Map<Long, ExamServiceClient.ItemInfo> infos = Map.of(5L, new ExamServiceClient.ItemInfo(5L, 4.0, "BINAIRE"));
            when(examServiceClient.getItemInfosForGrille(1L)).thenReturn(infos);

            // Exécution
            SaisirNotationRequest req = new SaisirNotationRequest(1L, STATION_ID, 1L, 5L, 1.0f);
            service.saisirNotation(req, EVAL_ID);

            // Vérification
            assertThat(n.getScore_final()).isEqualTo(4.0f); // 1.0 (valeur) * 4.0 (pondération)
        }
    }

    @Nested
    @DisplayName("validerEtudiant()")
    class ValiderEtudiantLogic {

        @Test
        @DisplayName("Si absent: score final 0 et suppression des items")
        void validerEtudiant_Absent() {
            ExamenParticipation p = new ExamenParticipation();
            p.setId(1L);

            RotationAssignment ra = new RotationAssignment();
            ra.setId(55L);

            Notation n = new Notation();
            n.setId(10L);

            when(participationRepository.findByEtudiantIdAndStationId(anyLong(), anyLong())).thenReturn(Optional.of(p));
            when(rotationAssignmentRepository.findByParticipationId(1L)).thenReturn(Optional.of(ra));
            when(notationRepository.findByAssignmentId(55L)).thenReturn(Optional.of(n));
            when(notationItemRepository.findByNotationId(10L)).thenReturn(List.of(new NotationItem()));

            ValiderEtudiantRequest req = new ValiderEtudiantRequest();
            req.setAbsent(true);

            service.validerEtudiant(1L, STATION_ID, EVAL_ID, req);

            assertThat(n.getScore_final()).isEqualTo(0.0f);
            assertThat(n.getVerouillee()).isTrue();
            assertThat(p.getEst_present()).isFalse();
            verify(notationItemRepository).deleteAll(anyList());
        }
    }

    @Nested
    @DisplayName("validerLot()")
    class ValiderLotLogic {

        @Test
        @DisplayName("Doit passer le lot ET les rotations à TERMINE")
        void validerLot_CascadeStatut() {
            Lot lot = new Lot();
            lot.setId(10L);
            lot.setEvaluateurId(EVAL_ID);

            StudentGroup sg = new StudentGroup();
            Rotation r = new Rotation();
            r.setStatut(RotationStatus.EN_ATTENTE);
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
        @DisplayName("Doit refuser si le lot n'appartient pas à l'évaluateur")
        void validerLot_Forbidden() {
            Lot lot = new Lot();
            lot.setEvaluateurId(999L); // Autre evaluateur
            when(lotRepository.findById(10L)).thenReturn(Optional.of(lot));

            assertThatThrownBy(() -> service.validerLot(10L, EVAL_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Accès refusé");
        }
    }
}
