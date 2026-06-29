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
import tn.epos.scoring_service.client.ExamGenerationView;
import tn.epos.scoring_service.client.ExamServiceClient;
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
        return new ExamGenerationView(EXAM_ID, LocalDate.of(2026, 6, 20),
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

        @Test
        @DisplayName("K=3, cap=4 → lotSize 12 ; 54 inscrits → 5 lots (4×12 + 1×6)")
        void repartit_54en5lots() {
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(exam("CONFIGURE", 3, 4));
            when(participationRepository.findByExamenId(EXAM_ID)).thenReturn(enrolled(54));

            RepartitionResult r = service.repartir(EXAM_ID);

            assertThat(r.lotSize()).isEqualTo(12);
            assertThat(r.lots()).isEqualTo(5);
            assertThat(r.etudiantsRepartis()).isEqualTo(54);
            assertThat(r.details()).hasSize(5);
            assertThat(r.details().subList(0, 4)).allMatch(d -> d.taille() == 12);
            assertThat(r.details().get(4).taille()).isEqualTo(6);
            assertThat(r.details().get(0).numeroLot()).isEqualTo(1);
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
}
