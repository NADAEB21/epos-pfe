package tn.epos.scoring_service.dto;

/**
 * Summary returned after marking a lot's presence (Phase 2). The lot transitions
 * to EN_COURS so its rotations can then be generated.
 */
public record PresenceResult(
        Long lotId,
        int total,
        int presents,
        int absents) {
}
