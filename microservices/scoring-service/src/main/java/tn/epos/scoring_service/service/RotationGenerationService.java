package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.common.exception.BusinessException;
import tn.epos.scoring_service.client.ExamGenerationView;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.dto.GenerationResult;
import tn.epos.scoring_service.entities.*;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;
import tn.epos.scoring_service.repositories.ILotRepository;
import tn.epos.scoring_service.repositories.IRotationAssignmentRepository;
import tn.epos.scoring_service.repositories.IRotationRepository;
import tn.epos.scoring_service.repositories.IStudentGroupRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generates the OSCE rotation plan for a single <b>lot</b> (Phase 2 of the
 * two-phase workflow — exam day, after that lot's presence is marked).
 *
 * <p>This is a deliberate gate flip from the original Option-B design (PR #130),
 * which generated once for the whole exam at CONFIGURE time. The real workflow is:
 * <ol>
 *   <li>CONFIGURE: students are partitioned into lots (waves) —
 *       {@link LotAssignmentService#repartir}.</li>
 *   <li>Exam day, per wave: mark presence —
 *       {@link LotAssignmentService#markPresence} flips the lot to EN_COURS.</li>
 *   <li>Exam day, per wave: generate <i>that lot's</i> circuit here, over only the
 *       students who actually showed up. No stale-plan trap, absences handled
 *       cleanly.</li>
 * </ol>
 *
 * <p>The circuit itself is the same Latin square as #130, now scoped to one lot:
 * <ul>
 *   <li>K stations → K student groups → K créneaux; every group visits every
 *       station exactly once and no station is idle.</li>
 *   <li>One {@link Rotation} per (station × créneau) carries the station's bound
 *       évaluateur and {@code debutCreneau} = the lot's wave start + t·durée.</li>
 *   <li>One {@link RotationAssignment} per (present student × station).</li>
 * </ul>
 *
 * <p>The lot's wave start is staggered back-to-back: lot {@code m} (1-based) runs
 * after the {@code m−1} preceding circuits, i.e.
 * {@code heureDebut + (m−1)·K·dureeStationMin}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RotationGenerationService {

    private static final LocalTime DEFAULT_START = LocalTime.of(9, 0);
    private static final int DEFAULT_DUREE_MIN = 15;
    private static final int DEFAULT_CAPACITE = 4;

    private final ExamServiceClient examServiceClient;
    private final IExamenParticipationRepository participationRepository;
    private final ILotRepository lotRepository;
    private final IStudentGroupRepository studentGroupRepository;
    private final IRotationRepository rotationRepository;
    private final IRotationAssignmentRepository assignmentRepository;

    @Transactional
    public GenerationResult generateForLot(Long lotId) {
        Lot lot = lotRepository.findById(lotId)
                .orElseThrow(() -> new BusinessException("Lot introuvable : " + lotId));

        ExamGenerationView exam = examServiceClient.getExamForGeneration(lot.getExamenId());

        // Gate flip: rotations are now an exam-day, per-lot action, not a
        // CONFIGURE-time exam-wide one. The exam must be launched (EN_COURS) and
        // the lot's presence must already be marked (lot EN_COURS).
        if (!"EN_COURS".equals(exam.statut())) {
            throw new BusinessException(
                    "Les rotations se génèrent le jour de l'examen, une fois celui-ci lancé "
                            + "(statut EN_COURS requis ; statut actuel : " + exam.statut() + ").");
        }
        if (lot.getStatut() != LotStatus.EN_COURS) {
            throw new BusinessException(
                    "Marquez d'abord la présence du lot " + lot.getNumeroLot()
                            + " avant de générer ses rotations.");
        }

        // Stations ordered by display order; nulls last for determinism.
        List<ExamGenerationView.StationView> stations = new ArrayList<>(exam.stations());
        stations.sort(Comparator.comparing(
                ExamGenerationView.StationView::ordre,
                Comparator.nullsLast(Comparator.naturalOrder())));
        int k = stations.size();
        if (k == 0) {
            throw new BusinessException(
                    "Aucune station : ajoutez au moins une station avant de générer les rotations.");
        }

        // Present students of THIS lot only (skip explicit absentees; null = present).
        List<ExamenParticipation> lotParticipations = participationRepository.findByLotId(lotId);
        List<ExamenParticipation> present = lotParticipations.stream()
                .filter(p -> !Boolean.FALSE.equals(p.getEst_present()))
                .sorted(Comparator.comparing(ExamenParticipation::getId))
                .toList();
        int n = present.size();
        int absents = lotParticipations.size() - n;
        if (n == 0) {
            throw new BusinessException(
                    "Aucun étudiant présent dans le lot " + lot.getNumeroLot() + ".");
        }

        // Re-runnable per lot: clear only THIS lot's prior groups (cascade to its
        // rotations/assignments). Other lots and the roster are untouched.
        wipeLotGroups(lotId);

        LocalTime start = exam.heureDebut() != null ? exam.heureDebut() : DEFAULT_START;
        LocalDate date = exam.dateExamen();
        int duree = exam.dureeStationMin() != null ? exam.dureeStationMin() : DEFAULT_DUREE_MIN;
        int capacite = exam.nbEtudiantsParStation() != null ? exam.nbEtudiantsParStation() : DEFAULT_CAPACITE;

        // Back-to-back waves: lot m (1-based) starts after the m-1 preceding circuits.
        LocalDateTime examStart = LocalDateTime.of(date, start);
        int waveIndex = (lot.getNumeroLot() != null ? lot.getNumeroLot() : 1) - 1;
        LocalDateTime waveStart = examStart.plusMinutes((long) waveIndex * k * duree);

        // Partition present students into K balanced groups (round-robin keeps sizes within 1).
        List<List<ExamenParticipation>> groupStudents = new ArrayList<>();
        for (int g = 0; g < k; g++) {
            groupStudents.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            groupStudents.get(i % k).add(present.get(i));
        }
        int maxGroupSize = (int) Math.ceil((double) n / k);
        String avertissement = maxGroupSize > capacite
                ? "Capacité dépassée : " + maxGroupSize + " étudiants/station alors que "
                        + capacite + " sont configurés. Ajoutez des stations ou réduisez l'effectif du lot."
                : null;

        // K student groups under the lot.
        List<StudentGroup> groups = new ArrayList<>();
        for (int g = 0; g < k; g++) {
            StudentGroup sg = new StudentGroup();
            sg.setNumeroGroupe(g + 1);
            sg.setLot(lot);
            groups.add(studentGroupRepository.save(sg));
        }

        // Latin square: at créneau t, group g sits at station (g + t) mod K.
        int rotationCount = 0;
        int assignmentCount = 0;
        for (int t = 0; t < k; t++) {
            LocalDateTime creneau = waveStart.plusMinutes((long) t * duree);
            for (int g = 0; g < k; g++) {
                ExamGenerationView.StationView station = stations.get((g + t) % k);
                Long evaluateurId = (station.evaluateurIds() != null && !station.evaluateurIds().isEmpty())
                        ? station.evaluateurIds().get(0)
                        : null;

                Rotation rotation = new Rotation();
                rotation.setStationId(station.id());
                rotation.setEvaluateurId(evaluateurId);
                rotation.setOrdrePassage(t + 1);
                rotation.setDebutCreneau(creneau);
                rotation.setStatut(RotationStatus.EN_ATTENTE);
                rotation.setStudentGroup(groups.get(g));
                rotation = rotationRepository.save(rotation);
                rotationCount++;

                for (ExamenParticipation p : groupStudents.get(g)) {
                    RotationAssignment a = new RotationAssignment();
                    a.setRotation(rotation);
                    a.setParticipation(p);
                    a.setPresenceConfirmee(true); // already confirmed present at lot level
                    assignmentRepository.save(a);
                    assignmentCount++;
                }
            }
        }

        log.info("Rotations générées pour le lot {} (examen {}) : {} groupes, {} rotations, {} assignments ({} présents, {} absents)",
                lotId, lot.getExamenId(), k, rotationCount, assignmentCount, n, absents);

        return new GenerationResult(1, k, k, k, rotationCount, assignmentCount, n, absents, avertissement);
    }

    /**
     * Purges the prior plan for a single lot. Deleting the lot's student groups
     * cascades to their rotations and assignments (DB ON DELETE CASCADE + JPA
     * cascade=ALL). The lot itself, its participations, and other lots are kept.
     */
    private void wipeLotGroups(Long lotId) {
        List<StudentGroup> groups = studentGroupRepository.findByLotId(lotId);
        studentGroupRepository.deleteAll(groups);
    }
}
