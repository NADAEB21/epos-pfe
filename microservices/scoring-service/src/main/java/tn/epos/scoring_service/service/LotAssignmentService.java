package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.common.exception.BusinessException;
import tn.epos.scoring_service.client.ExamGenerationView;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.dto.PresenceResult;
import tn.epos.scoring_service.dto.RepartitionResult;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.entities.LotStatus;
import tn.epos.scoring_service.entities.StudentGroup;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;
import tn.epos.scoring_service.repositories.ILotRepository;
import tn.epos.scoring_service.repositories.IStudentGroupRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 1 + the presence half of Phase 2 of the two-phase OSCE workflow.
 *
 * <p><b>Phase 1 — Répartition en lots (CONFIGURE, pre-exam, stable):</b> a
 * <i>lot</i> is a wave of students who run the whole circuit together at a
 * scheduled time. {@link #repartir(Long)} partitions the enrolled roster into
 * lots of {@code K stations × nbEtudiantsParStation}, wiring
 * {@code participation.lot}. Students learn their lot + arrival window ahead of
 * the exam; no presence is assumed and NO rotations are generated yet — that is
 * the day-of, per-lot job of {@code RotationGenerationService.generateForLot}.
 *
 * <p><b>Phase 2 — Présence par lot (EXAM DAY):</b> {@link #markPresence} records
 * who actually showed up for a wave and flips the lot to EN_COURS, unlocking its
 * rotation generation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LotAssignmentService {

    private static final int DEFAULT_CAPACITE = 4;

    private final ExamServiceClient examServiceClient;
    private final IExamenParticipationRepository participationRepository;
    private final ILotRepository lotRepository;
    private final IStudentGroupRepository studentGroupRepository;

    /**
     * Partitions the enrolled roster of {@code examenId} into lots. Re-runnable
     * at CONFIGURE — it wipes any prior lots (and any plan generated under them)
     * and re-detaches every participation before repartitioning. Gated to
     * CONFIGURE: lots are a pre-exam deliverable and are frozen once the exam is
     * launched.
     */
    @Transactional
    public RepartitionResult repartir(Long examenId) {
        ExamGenerationView exam = examServiceClient.getExamForGeneration(examenId);

        if (!"CONFIGURE".equals(exam.statut())) {
            throw new BusinessException(
                    "La répartition en lots n'est possible qu'au statut CONFIGURE (statut actuel : "
                            + exam.statut() + ").");
        }

        int k = exam.stations().size();
        if (k == 0) {
            throw new BusinessException(
                    "Aucune station : ajoutez au moins une station avant de répartir les lots.");
        }
        int capacite = exam.nbEtudiantsParStation() != null ? exam.nbEtudiantsParStation() : DEFAULT_CAPACITE;
        int lotSize = k * capacite;

        List<ExamenParticipation> enrolled = participationRepository.findByExamenId(examenId).stream()
                .sorted(Comparator.comparing(ExamenParticipation::getId))
                .toList();
        int n = enrolled.size();
        if (n == 0) {
            throw new BusinessException("Aucun étudiant inscrit à répartir.");
        }

        // Re-runnable: detach participations + drop any prior lots/plan first.
        wipeLots(examenId);

        int nbLots = (int) Math.ceil((double) n / lotSize);
        List<RepartitionResult.LotInfo> details = new ArrayList<>();
        for (int m = 0; m < nbLots; m++) {
            int from = m * lotSize;
            int to = Math.min(from + lotSize, n);

            Lot lot = new Lot();
            lot.setExamenId(examenId);
            lot.setNumeroLot(m + 1);
            lot.setTailleLot(to - from);
            lot.setStatut(LotStatus.EN_ATTENTE);
            lot = lotRepository.save(lot);

            for (int i = from; i < to; i++) {
                ExamenParticipation p = enrolled.get(i);
                p.setLot(lot);
                participationRepository.save(p);
            }
            details.add(new RepartitionResult.LotInfo(lot.getId(), lot.getNumeroLot(), lot.getTailleLot()));
        }

        log.info("Répartition examen {} : {} étudiants → {} lots de {} max (K={}, capacité={})",
                examenId, n, nbLots, lotSize, k, capacite);
        return new RepartitionResult(nbLots, lotSize, n, details);
    }

    /**
     * Marks presence for a whole lot on exam day. Everyone in the lot is set
     * present except the participations listed in {@code absentParticipationIds};
     * the lot then transitions to EN_COURS so its rotations can be generated.
     * Idempotent — safe to re-call as latecomers arrive.
     */
    @Transactional
    public PresenceResult markPresence(Long lotId, List<Long> absentParticipationIds) {
        Lot lot = lotRepository.findById(lotId)
                .orElseThrow(() -> new BusinessException("Lot introuvable : " + lotId));

        List<ExamenParticipation> participations = participationRepository.findByLotId(lotId);
        if (participations.isEmpty()) {
            throw new BusinessException(
                    "Le lot " + lotId + " ne contient aucun étudiant ; répartissez les lots d'abord.");
        }

        Set<Long> absents = absentParticipationIds == null
                ? Set.of()
                : new HashSet<>(absentParticipationIds);

        int presents = 0;
        int absentCount = 0;
        for (ExamenParticipation p : participations) {
            boolean isPresent = !absents.contains(p.getId());
            p.setEst_present(isPresent);
            participationRepository.save(p);
            if (isPresent) {
                presents++;
            } else {
                absentCount++;
            }
        }

        lot.setStatut(LotStatus.EN_COURS);
        lotRepository.save(lot);

        log.info("Présence lot {} : {} présents / {} absents sur {}",
                lotId, presents, absentCount, participations.size());
        return new PresenceResult(lotId, participations.size(), presents, absentCount);
    }

    /**
     * Removes every lot of an exam: detaches participations (their {@code lot_id}
     * FK has no ON DELETE CASCADE, so it must be nulled first), then deletes the
     * student groups (which cascade to rotations/assignments), then the lots.
     * Roster participations themselves are preserved.
     */
    private void wipeLots(Long examenId) {
        List<Lot> existing = lotRepository.findByExamenId(examenId);
        for (Lot lot : existing) {
            for (ExamenParticipation p : participationRepository.findByLotId(lot.getId())) {
                p.setLot(null);
                participationRepository.save(p);
            }
            List<StudentGroup> groups = studentGroupRepository.findByLotId(lot.getId());
            studentGroupRepository.deleteAll(groups);
        }
        lotRepository.deleteAll(existing);
    }
}
