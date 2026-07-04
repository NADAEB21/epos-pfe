package tn.epos.scoring_service.dto;

import tn.epos.scoring_service.entities.NotationAdjustment;

import java.time.LocalDateTime;

/**
 * Read-only view of a {@link NotationAdjustment} for the responsable dashboard
 * history panel (ADR-0013 Part 2).
 */
public record NotationAdjustmentDTO(
        Long id,
        Long notationId,
        Long itemId,
        Float ancienneValeur,
        Float nouvelleValeur,
        Float ancienScore,
        Float nouveauScore,
        String motif,
        Long adjustedByUserId,
        LocalDateTime adjustedAt
) {
    public static NotationAdjustmentDTO fromEntity(NotationAdjustment a) {
        if (a == null) return null;
        return new NotationAdjustmentDTO(
                a.getId(),
                a.getNotationId(),
                a.getItemId(),
                a.getAncienneValeur(),
                a.getNouvelleValeur(),
                a.getAncienScore(),
                a.getNouveauScore(),
                a.getMotif(),
                a.getAdjustedByUserId(),
                a.getAdjustedAt());
    }
}
