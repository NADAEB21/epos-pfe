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
 * Auto-generates the OSCE rotation plan for an exam (Option B — see Phase C
 * roadmap). Reads the exam's stations + timing cross-service from exam-service,
 * its participations locally, and builds a Latin-square round-robin:
 *
 * <ul>
 *   <li>K stations → K student groups → K créneaux, so every group visits every
 *       station exactly once and no station is ever idle.</li>
 *   <li>One {@link Rotation} per (station × créneau) carries the station's bound
 *       évaluateur and a {@code debutCreneau} = exam date + heureDebut + t·durée.</li>
 *   <li>One {@link RotationAssignment} per (present student × station).</li>
 * </ul>
 *
 * <p>Gated to {@code CONFIGURE}: the circuit is frozen once an exam goes
 * EN_COURS. Re-runnable at CONFIGURE — wipes the prior plan first (no notations
 * exist yet at CONFIGURE, so nothing graded is destroyed).
 *
 * <p>Absent students ({@code est_present = false}) are skipped per the build
 * decision (2026-06-09); a null presence is treated as present.
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
    public GenerationResult generate(Long examenId) {
        ExamGenerationView exam = examServiceClient.getExamForGeneration(examenId);

        // Gate: rotations are a CONFIGURE-time action; once EN_COURS the plan is frozen.
        if (!"CONFIGURE".equals(exam.statut())) {
            throw new BusinessException(
                    "Les rotations ne peuvent être générées qu'au statut CONFIGURE (statut actuel : "
                            + exam.statut() + ").");
        }

        // Stations ordered by their display order; nulls last for determinism.
        List<ExamGenerationView.StationView> stations = new ArrayList<>(exam.stations());
        stations.sort(Comparator.comparing(
                ExamGenerationView.StationView::ordre,
                Comparator.nullsLast(Comparator.naturalOrder())));
        int k = stations.size();
        if (k == 0) {
            throw new BusinessException(
                    "Aucune station : ajoutez au moins une station avant de générer les rotations.");
        }

        // Present students only (skip explicit absentees; null = present).
        List<ExamenParticipation> all = participationRepository.findByExamenId(examenId);
        List<ExamenParticipation> present = all.stream()
                .filter(p -> !Boolean.FALSE.equals(p.getEst_present()))
                .sorted(Comparator.comparing(ExamenParticipation::getId))
                .toList();
        int n = present.size();
        int absents = all.size() - n;
        if (n == 0) {
            throw new BusinessException("Aucun étudiant présent à répartir.");
        }

        // Re-runnable: clear any prior plan for this exam before rebuilding.
        wipeExisting(examenId);

        LocalTime start = exam.heureDebut() != null ? exam.heureDebut() : DEFAULT_START;
        LocalDate date = exam.dateExamen();
        LocalDateTime examStart = LocalDateTime.of(date, start);
        int duree = exam.dureeStationMin() != null ? exam.dureeStationMin() : DEFAULT_DUREE_MIN;
        int capacite = exam.nbEtudiantsParStation() != null ? exam.nbEtudiantsParStation() : DEFAULT_CAPACITE;

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
                        + capacite + " sont configurés. Ajoutez des stations ou réduisez l'effectif."
                : null;

        // One Lot for the whole cohort of this generation.
        Lot lot = new Lot();
        lot.setExamenId(examenId);
        lot.setNumeroLot(1);
        lot.setTailleLot(n);
        lot.setStatut(LotStatus.EN_ATTENTE);
        lot = lotRepository.save(lot);

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
            LocalDateTime creneau = examStart.plusMinutes((long) t * duree);
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
                    a.setPresenceConfirmee(false); // confirmed live on exam day via PATCH /presence
                    assignmentRepository.save(a);
                    assignmentCount++;
                }
            }
        }

        log.info("Rotations générées pour l'examen {} : {} groupes, {} rotations, {} assignments ({} présents, {} absents)",
                examenId, k, rotationCount, assignmentCount, n, absents);

        return new GenerationResult(1, k, k, k, rotationCount, assignmentCount, n, absents, avertissement);
    }

    /**
     * Purges the prior plan for this exam. Deleting the student groups cascades
     * to their rotations and assignments via the DB ON DELETE CASCADE chain
     * (and the JPA cascade=ALL mappings); the lots are removed last. Roster
     * participations are untouched (their lot_id is never set by generation).
     */
    private void wipeExisting(Long examenId) {
        List<Lot> existing = lotRepository.findByExamenId(examenId);
        for (Lot lot : existing) {
            List<StudentGroup> groups = studentGroupRepository.findByLotId(lot.getId());
            studentGroupRepository.deleteAll(groups);
        }
        lotRepository.deleteAll(existing);
    }
}
