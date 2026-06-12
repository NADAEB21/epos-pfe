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
import tn.epos.scoring_service.client.ExamGenerationView;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.dto.GenerationResult;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.entities.LotStatus;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationAssignment;
import tn.epos.scoring_service.entities.StudentGroup;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;
import tn.epos.scoring_service.repositories.ILotRepository;
import tn.epos.scoring_service.repositories.IRotationAssignmentRepository;
import tn.epos.scoring_service.repositories.IRotationRepository;
import tn.epos.scoring_service.repositories.IStudentGroupRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RotationGenerationService - génération per-lot (Phase 2, jour J)")
class RotationGenerationServiceTest {

    @Mock private ExamServiceClient examServiceClient;
    @Mock private IExamenParticipationRepository participationRepository;
    @Mock private ILotRepository lotRepository;
    @Mock private IStudentGroupRepository studentGroupRepository;
    @Mock private IRotationRepository rotationRepository;
    @Mock private IRotationAssignmentRepository assignmentRepository;

    @InjectMocks private RotationGenerationService service;

    private final List<Rotation> savedRotations = new ArrayList<>();
    private final List<RotationAssignment> savedAssignments = new ArrayList<>();

    private static final long EXAM_ID = 100L;
    private static final long LOT_ID = 5L;

    @BeforeEach
    void setUp() {
        savedRotations.clear();
        savedAssignments.clear();

        // No prior groups for this lot by default (re-run wipe tested separately).
        lenient().when(studentGroupRepository.findByLotId(LOT_ID)).thenReturn(List.of());

        AtomicLong groupSeq = new AtomicLong(1);
        lenient().when(studentGroupRepository.save(any(StudentGroup.class))).thenAnswer(inv -> {
            StudentGroup g = inv.getArgument(0);
            if (g.getId() == null) g.setId(groupSeq.getAndIncrement());
            return g;
        });
        AtomicLong rotSeq = new AtomicLong(1);
        lenient().when(rotationRepository.save(any(Rotation.class))).thenAnswer(inv -> {
            Rotation r = inv.getArgument(0);
            if (r.getId() == null) r.setId(rotSeq.getAndIncrement());
            savedRotations.add(r);
            return r;
        });
        lenient().when(assignmentRepository.save(any(RotationAssignment.class))).thenAnswer(inv -> {
            RotationAssignment a = inv.getArgument(0);
            savedAssignments.add(a);
            return a;
        });
    }

    private Lot lot(int numeroLot, LotStatus statut) {
        Lot l = new Lot();
        l.setId(LOT_ID);
        l.setExamenId(EXAM_ID);
        l.setNumeroLot(numeroLot);
        l.setStatut(statut);
        return l;
    }

    private ExamGenerationView exam(String statut, int nbStations, Integer capacite,
                                    LocalDate date, LocalTime heure, int duree) {
        // launched_at null by default → generation anchors on the planned start
        // (dateExamen + heureDebut), the pre-ADR-0010 behaviour these timings assert.
        return exam(statut, nbStations, capacite, date, heure, null, duree);
    }

    private ExamGenerationView exam(String statut, int nbStations, Integer capacite,
                                    LocalDate date, LocalTime heure, LocalDateTime launchedAt, int duree) {
        List<ExamGenerationView.StationView> stations = new ArrayList<>();
        for (int i = 0; i < nbStations; i++) {
            // station id = 10+i, ordre = i+1, one bound évaluateur = 1000+i
            stations.add(new ExamGenerationView.StationView(
                    10L + i, i + 1, List.of(1000L + i)));
        }
        return new ExamGenerationView(EXAM_ID, date, heure, launchedAt, duree, capacite, statut, stations);
    }

    private List<ExamenParticipation> participations(int present, int absent) {
        List<ExamenParticipation> list = new ArrayList<>();
        long id = 1;
        for (int i = 0; i < present; i++) {
            ExamenParticipation p = new ExamenParticipation();
            p.setId(id++);
            p.setEst_present(true);
            list.add(p);
        }
        for (int i = 0; i < absent; i++) {
            ExamenParticipation p = new ExamenParticipation();
            p.setId(id++);
            p.setEst_present(false);
            list.add(p);
        }
        return list;
    }

    @Nested
    @DisplayName("Cas nominal — circuit Latin square du lot")
    class HappyPath {

        @Test
        @DisplayName("3 stations × 6 présents → 3 groupes, 9 rotations, 18 assignments")
        void genere_circuitComplet() {
            when(lotRepository.findById(LOT_ID)).thenReturn(Optional.of(lot(1, LotStatus.EN_COURS)));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(
                    exam("EN_COURS", 3, 4, LocalDate.of(2026, 6, 20), LocalTime.of(9, 0), 15));
            when(participationRepository.findByLotId(LOT_ID)).thenReturn(participations(6, 0));

            GenerationResult r = service.generateForLot(LOT_ID);

            assertThat(r.lots()).isEqualTo(1);
            assertThat(r.groupes()).isEqualTo(3);
            assertThat(r.stations()).isEqualTo(3);
            assertThat(r.creneaux()).isEqualTo(3);
            assertThat(r.rotations()).isEqualTo(9);       // K² = 3²
            assertThat(r.assignments()).isEqualTo(18);    // N×K = 6×3
            assertThat(r.etudiantsPresents()).isEqualTo(6);
            assertThat(r.etudiantsAbsents()).isZero();
            assertThat(r.avertissement()).isNull();

            verify(studentGroupRepository, org.mockito.Mockito.times(3)).save(any(StudentGroup.class));
        }

        @Test
        @DisplayName("Latin square : chaque station hôte d'un seul groupe par créneau, chaque groupe visite chaque station une fois")
        void genere_proprietesLatinSquare() {
            when(lotRepository.findById(LOT_ID)).thenReturn(Optional.of(lot(1, LotStatus.EN_COURS)));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(
                    exam("EN_COURS", 3, 4, LocalDate.of(2026, 6, 20), LocalTime.of(9, 0), 15));
            when(participationRepository.findByLotId(LOT_ID)).thenReturn(participations(6, 0));

            service.generateForLot(LOT_ID);

            // Each station appears in exactly 3 rotations (one per créneau).
            var byStation = savedRotations.stream()
                    .collect(Collectors.groupingBy(Rotation::getStationId, Collectors.counting()));
            assertThat(byStation).hasSize(3);
            assertThat(byStation.values()).allMatch(c -> c == 3L);

            // Each group visits all 3 distinct stations exactly once.
            var byGroup = savedRotations.stream()
                    .collect(Collectors.groupingBy(r -> r.getStudentGroup().getNumeroGroupe()));
            assertThat(byGroup).hasSize(3);
            byGroup.values().forEach(rots -> {
                assertThat(rots).hasSize(3);
                assertThat(rots.stream().map(Rotation::getStationId).distinct().count()).isEqualTo(3);
            });

            // Each (station, créneau) is unique — no double-booking.
            long distinctSlots = savedRotations.stream()
                    .map(r -> r.getStationId() + "@" + r.getDebutCreneau())
                    .distinct().count();
            assertThat(distinctSlots).isEqualTo(9);
        }

        @Test
        @DisplayName("Lot 1 : créneaux à heureDebut, +durée, +2·durée ; assignments confirmés présents")
        void genere_timingLot1() {
            when(lotRepository.findById(LOT_ID)).thenReturn(Optional.of(lot(1, LotStatus.EN_COURS)));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(
                    exam("EN_COURS", 3, 4, LocalDate.of(2026, 6, 20), LocalTime.of(9, 0), 15));
            when(participationRepository.findByLotId(LOT_ID)).thenReturn(participations(6, 0));

            service.generateForLot(LOT_ID);

            // Station 10 → évaluateur 1000, in all its rotations.
            assertThat(savedRotations.stream().filter(r -> r.getStationId() == 10L))
                    .allMatch(r -> r.getEvaluateurId() == 1000L);

            var starts = savedRotations.stream().map(Rotation::getDebutCreneau)
                    .distinct().sorted().toList();
            assertThat(starts).containsExactly(
                    LocalDateTime.of(2026, 6, 20, 9, 0),
                    LocalDateTime.of(2026, 6, 20, 9, 15),
                    LocalDateTime.of(2026, 6, 20, 9, 30));

            // Lot-level presence already confirmed → assignments start confirmed.
            assertThat(savedAssignments).allMatch(a -> Boolean.TRUE.equals(a.getPresenceConfirmee()));
        }

        @Test
        @DisplayName("ADR-0010 : créneaux ancrés sur launched_at (lancement tardif) et non sur le départ planifié")
        void genere_ancreSurLaunchedAt() {
            // Planifié 09:00 mais lancé réellement à 09:37 → les créneaux du lot 1
            // doivent partir de 09:37, pas de 09:00.
            LocalDateTime launchedAt = LocalDateTime.of(2026, 6, 20, 9, 37);
            when(lotRepository.findById(LOT_ID)).thenReturn(Optional.of(lot(1, LotStatus.EN_COURS)));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(
                    exam("EN_COURS", 3, 4, LocalDate.of(2026, 6, 20), LocalTime.of(9, 0), launchedAt, 15));
            when(participationRepository.findByLotId(LOT_ID)).thenReturn(participations(6, 0));

            service.generateForLot(LOT_ID);

            var starts = savedRotations.stream().map(Rotation::getDebutCreneau)
                    .distinct().sorted().toList();
            assertThat(starts).containsExactly(
                    LocalDateTime.of(2026, 6, 20, 9, 37),
                    LocalDateTime.of(2026, 6, 20, 9, 52),
                    LocalDateTime.of(2026, 6, 20, 10, 7));
        }

        @Test
        @DisplayName("ADR-0010 : lot 2 décalé back-to-back depuis launched_at (09:37 + 3·15 = 10:22)")
        void genere_lot2AncreSurLaunchedAt() {
            LocalDateTime launchedAt = LocalDateTime.of(2026, 6, 20, 9, 37);
            when(lotRepository.findById(LOT_ID)).thenReturn(Optional.of(lot(2, LotStatus.EN_COURS)));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(
                    exam("EN_COURS", 3, 4, LocalDate.of(2026, 6, 20), LocalTime.of(9, 0), launchedAt, 15));
            when(participationRepository.findByLotId(LOT_ID)).thenReturn(participations(6, 0));

            service.generateForLot(LOT_ID);

            var starts = savedRotations.stream().map(Rotation::getDebutCreneau)
                    .distinct().sorted().toList();
            assertThat(starts).containsExactly(
                    LocalDateTime.of(2026, 6, 20, 10, 22),
                    LocalDateTime.of(2026, 6, 20, 10, 37),
                    LocalDateTime.of(2026, 6, 20, 10, 52));
        }

        @Test
        @DisplayName("Lot 2 : décalé back-to-back de K·durée après le lot 1 (09:00 + 3·15 = 09:45)")
        void genere_decalageVagueLot2() {
            when(lotRepository.findById(LOT_ID)).thenReturn(Optional.of(lot(2, LotStatus.EN_COURS)));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(
                    exam("EN_COURS", 3, 4, LocalDate.of(2026, 6, 20), LocalTime.of(9, 0), 15));
            when(participationRepository.findByLotId(LOT_ID)).thenReturn(participations(6, 0));

            service.generateForLot(LOT_ID);

            var starts = savedRotations.stream().map(Rotation::getDebutCreneau)
                    .distinct().sorted().toList();
            assertThat(starts).containsExactly(
                    LocalDateTime.of(2026, 6, 20, 9, 45),
                    LocalDateTime.of(2026, 6, 20, 10, 0),
                    LocalDateTime.of(2026, 6, 20, 10, 15));
        }
    }

    @Nested
    @DisplayName("Effectifs")
    class Effectifs {

        @Test
        @DisplayName("Les absents (est_present=false) du lot sont exclus")
        void genere_excluAbsents() {
            when(lotRepository.findById(LOT_ID)).thenReturn(Optional.of(lot(1, LotStatus.EN_COURS)));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(
                    exam("EN_COURS", 2, 4, LocalDate.of(2026, 6, 20), LocalTime.of(9, 0), 15));
            when(participationRepository.findByLotId(LOT_ID)).thenReturn(participations(4, 3));

            GenerationResult r = service.generateForLot(LOT_ID);

            assertThat(r.etudiantsPresents()).isEqualTo(4);
            assertThat(r.etudiantsAbsents()).isEqualTo(3);
            assertThat(r.assignments()).isEqualTo(8); // 4 présents × 2 stations
            assertThat(savedAssignments.stream().map(a -> a.getParticipation().getId()))
                    .allMatch(id -> id <= 4);
        }

        @Test
        @DisplayName("Capacité dépassée → avertissement non bloquant")
        void genere_avertissementCapacite() {
            // 2 stations, 6 présents → groupes de 3 > capacité 2.
            when(lotRepository.findById(LOT_ID)).thenReturn(Optional.of(lot(1, LotStatus.EN_COURS)));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(
                    exam("EN_COURS", 2, 2, LocalDate.of(2026, 6, 20), LocalTime.of(9, 0), 15));
            when(participationRepository.findByLotId(LOT_ID)).thenReturn(participations(6, 0));

            GenerationResult r = service.generateForLot(LOT_ID);

            assertThat(r.avertissement()).contains("Capacité dépassée");
            assertThat(r.assignments()).isEqualTo(12); // génère quand même
        }
    }

    @Nested
    @DisplayName("Garde-fous")
    class Guards {

        @Test
        @DisplayName("Lot introuvable → BusinessException")
        void genere_rejetteSiLotIntrouvable() {
            when(lotRepository.findById(LOT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.generateForLot(LOT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Lot introuvable");
        }

        @Test
        @DisplayName("Examen ≠ EN_COURS → BusinessException, aucune écriture")
        void genere_rejetteSiExamenPasEnCours() {
            when(lotRepository.findById(LOT_ID)).thenReturn(Optional.of(lot(1, LotStatus.EN_COURS)));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(
                    exam("CONFIGURE", 3, 4, LocalDate.of(2026, 6, 20), LocalTime.of(9, 0), 15));

            assertThatThrownBy(() -> service.generateForLot(LOT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("EN_COURS");

            verify(studentGroupRepository, never()).save(any());
        }

        @Test
        @DisplayName("Présence du lot non marquée (lot EN_ATTENTE) → BusinessException")
        void genere_rejetteSiPresenceNonMarquee() {
            when(lotRepository.findById(LOT_ID)).thenReturn(Optional.of(lot(1, LotStatus.EN_ATTENTE)));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(
                    exam("EN_COURS", 3, 4, LocalDate.of(2026, 6, 20), LocalTime.of(9, 0), 15));

            assertThatThrownBy(() -> service.generateForLot(LOT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("présence");

            verify(studentGroupRepository, never()).save(any());
        }

        @Test
        @DisplayName("Aucune station → BusinessException")
        void genere_rejetteSiAucuneStation() {
            when(lotRepository.findById(LOT_ID)).thenReturn(Optional.of(lot(1, LotStatus.EN_COURS)));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(
                    exam("EN_COURS", 0, 4, LocalDate.of(2026, 6, 20), LocalTime.of(9, 0), 15));

            assertThatThrownBy(() -> service.generateForLot(LOT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("station");
        }

        @Test
        @DisplayName("Aucun étudiant présent dans le lot → BusinessException")
        void genere_rejetteSiAucunPresent() {
            when(lotRepository.findById(LOT_ID)).thenReturn(Optional.of(lot(1, LotStatus.EN_COURS)));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(
                    exam("EN_COURS", 3, 4, LocalDate.of(2026, 6, 20), LocalTime.of(9, 0), 15));
            when(participationRepository.findByLotId(LOT_ID)).thenReturn(participations(0, 5));

            assertThatThrownBy(() -> service.generateForLot(LOT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("présent");
        }
    }

    @Nested
    @DisplayName("Idempotence")
    class Idempotence {

        @Test
        @DisplayName("Régénération : purge uniquement les groupes de CE lot, sans toucher au lot ni au roster")
        void genere_purgeGroupesDuLotSeulement() {
            StudentGroup ancienGroupe = new StudentGroup();
            ancienGroupe.setId(70L);

            when(lotRepository.findById(LOT_ID)).thenReturn(Optional.of(lot(1, LotStatus.EN_COURS)));
            when(examServiceClient.getExamForGeneration(EXAM_ID)).thenReturn(
                    exam("EN_COURS", 2, 4, LocalDate.of(2026, 6, 20), LocalTime.of(9, 0), 15));
            when(participationRepository.findByLotId(LOT_ID)).thenReturn(participations(4, 0));
            when(studentGroupRepository.findByLotId(LOT_ID)).thenReturn(List.of(ancienGroupe));

            service.generateForLot(LOT_ID);

            verify(studentGroupRepository).deleteAll(List.of(ancienGroupe));
            // The lot itself is never deleted on per-lot regeneration.
            verify(lotRepository, never()).deleteAll(any());
        }
    }
}
