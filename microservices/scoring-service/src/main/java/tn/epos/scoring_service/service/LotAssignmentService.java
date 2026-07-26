package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.client.ExamGenerationView;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.dto.LotDTO;
import tn.epos.scoring_service.dto.ParticipationDTO;
import tn.epos.scoring_service.dto.PresenceResult;
import tn.epos.scoring_service.dto.RepartitionResult;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.entities.LotStatus;
import tn.epos.scoring_service.entities.StudentGroup;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;
import tn.epos.scoring_service.repositories.ILotRepository;
import tn.epos.scoring_service.repositories.IStudentGroupRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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

        // #256 — l'ordre du LISTING importé, pas l'ordre technique d'insertion : les
        // ajouts manuels (ordre_import NULL) passent APRÈS le fichier, à ordre d'ajout.
        List<ExamenParticipation> enrolled = participationRepository.findByExamenId(examenId).stream()
                .sorted(Comparator
                        .comparing(ExamenParticipation::getOrdre_import,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ExamenParticipation::getId))
                .toList();
        int n = enrolled.size();
        if (n == 0) {
            throw new BusinessException("Aucun étudiant inscrit à répartir.");
        }

        // Re-runnable: detach participations + drop any prior lots/plan first.
        wipeLots(examenId);

        // #256 — remplissage SÉQUENTIEL par blocs pleins, dernier lot = le reste.
        // RENVERSE #164 en connaissance de cause (décision encadrant, 2026-07-22,
        // confirmée par Nada le 2026-07-24) : la faculté veut que les lots SUIVENT le
        // listing — lot 1 = les lotSize premières lignes, etc. — et un dernier lot de
        // 3 est PRÉFÉRÉ à des tailles 18/17/19 : c'est la place de repli naturelle
        // d'un retardataire ou d'un excusé. #164 égalisait la charge des stations ;
        // l'usage réel privilégie un listing lisible. Ne pas « re-corriger » vers
        // l'équilibrage : le test d'époque a été réécrit avec cette raison.
        int nbLots = (int) Math.ceil((double) n / lotSize);
        List<RepartitionResult.LotInfo> details = new ArrayList<>();
        int curseur = 0;
        for (int m = 0; m < nbLots; m++) {
            int taille = Math.min(lotSize, n - curseur);
            int from = curseur;
            int to = curseur + taille;
            curseur = to;

            Lot lot = new Lot();
            lot.setExamenId(examenId);
            lot.setNumeroLot(m + 1);
            lot.setTailleLot(taille);
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
     * Manually moves ONE enrolled student from their current lot to
     * {@code targetLotId} (#165), without the wipe-and-redo of {@link #repartir}.
     * Lets the responsable fix a single misplacement (a latecomer's wave, a
     * balancing tweak) instead of re-partitioning the whole roster.
     *
     * <p>Gated to CONFIGURE — the same pre-exam window répartition is allowed —
     * because once the exam is launched the per-lot Latin-square circuit is built
     * against lot membership, so a late move would desync the generated plan.
     *
     * <p>Re-points the participation's {@code lot_id} and recomputes
     * {@code tailleLot} on BOTH the source and target lot from their live
     * membership (tailleLot is the current member count, set that way by
     * {@link #repartir}, not a fixed capacity).
     *
     * @throws ResourceNotFoundException (404) unknown participation or target lot
     * @throws BusinessException (400) target lot is in another exam, or exam not CONFIGURE
     */
    @Transactional
    public ParticipationDTO deplacerEtudiant(Long targetLotId, Long participationId) {
        ExamenParticipation participation = participationRepository.findById(participationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Participation non trouvée avec l'id : " + participationId));
        Lot target = lotRepository.findById(targetLotId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Lot non trouvé avec l'id : " + targetLotId));

        // Same-exam guard: a lot_id must never cross exams (the participation's
        // examen_id is the source of truth — it is always set on enrolment).
        if (!Objects.equals(target.getExamenId(), participation.getExamen_id())) {
            throw new BusinessException(
                    "Le lot cible appartient à un autre examen que celui de l'étudiant.");
        }

        ExamGenerationView exam = examServiceClient.getExamForGeneration(participation.getExamen_id());
        if (!"CONFIGURE".equals(exam.statut())) {
            throw new BusinessException(
                    "Le déplacement d'un étudiant entre lots n'est possible qu'au statut CONFIGURE "
                            + "(statut actuel : " + exam.statut() + ").");
        }

        Lot source = participation.getLot();
        if (source != null && Objects.equals(source.getId(), targetLotId)) {
            return ParticipationDTO.fromEntity(participation); // déjà dans ce lot — no-op
        }

        participation.setLot(target);
        participationRepository.save(participation);

        // tailleLot is a live count → recompute both ends from actual membership.
        recomputeTailleLot(target);
        if (source != null) {
            recomputeTailleLot(source);
        }

        log.info("Déplacement participation {} vers lot {} (examen {})",
                participationId, targetLotId, participation.getExamen_id());
        return ParticipationDTO.fromEntity(participation);
    }

    /** Resets a lot's {@code tailleLot} to its current live membership count. */
    private void recomputeTailleLot(Lot lot) {
        lot.setTailleLot(participationRepository.findByLotId(lot.getId()).size());
        lotRepository.save(lot);
    }

    /**
     * #147 / ADR-0014-A §5 — assigns (or clears) the day a lot runs on. {@code null}
     * explicitly re-attaches the lot to the exam's own {@code dateExamen} — the
     * single-day default — which the PATCH-semantics of {@code PUT /api/lots/{id}}
     * cannot express (a null there means "leave untouched", #215).
     *
     * <p>Gated to CONFIGURE like répartition and déplacement: the day is a plan-phase
     * promise printed on convocations; once the exam is launched the launch day-gate
     * has already consumed it.
     */
    @Transactional
    public LotDTO changerJour(Long lotId, LocalDate jour) {
        Lot lot = lotRepository.findById(lotId)
                .orElseThrow(() -> new ResourceNotFoundException("Lot non trouvé avec l'id : " + lotId));

        ExamGenerationView exam = examServiceClient.getExamForGeneration(lot.getExamenId());
        if (!"CONFIGURE".equals(exam.statut())) {
            throw new BusinessException(
                    "Le jour d'un lot ne se modifie qu'au statut CONFIGURE (statut actuel : "
                            + exam.statut() + ").");
        }

        lot.setJour(jour);
        Lot saved = lotRepository.save(lot);
        log.info("Lot {} (examen {}) : jour {} .", lotId, lot.getExamenId(),
                jour == null ? "réinitialisé au jour de l'examen" : "fixé au " + jour);
        return LotDTO.fromEntity(saved);
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
