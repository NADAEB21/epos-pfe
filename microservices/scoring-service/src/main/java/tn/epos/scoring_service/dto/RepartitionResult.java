package tn.epos.scoring_service.dto;

import java.util.List;

/**
 * Summary returned by lot répartition (Phase 1 — CONFIGURE-time). Partitions the
 * enrolled roster into waves of {@code lotSize = K stations × nbEtudiantsParStation}.
 * No rotations are generated here — that is a per-lot, exam-day action (Phase 2).
 *
 * <p>The frontend derives each lot's arrival window from the exam's
 * {@code heureDebut} + {@code (numeroLot − 1) × K × dureeStationMin} (back-to-back
 * waves), so no start time is stored on the Lot.
 */
public record RepartitionResult(
        int lots,
        int lotSize,
        int etudiantsRepartis,
        List<LotInfo> details) {

    /** One created lot: its id, wave number, and how many students landed in it. */
    public record LotInfo(Long lotId, int numeroLot, int taille) {
    }
}
