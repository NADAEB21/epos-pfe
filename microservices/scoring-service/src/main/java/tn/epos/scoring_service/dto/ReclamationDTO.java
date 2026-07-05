package tn.epos.scoring_service.dto;

import tn.epos.scoring_service.entities.Reclamation;
import tn.epos.scoring_service.entities.ReclamationStatus;

import java.time.LocalDateTime;

/**
 * Read-only view of a {@link Reclamation} for the responsable register (#136).
 */
public record ReclamationDTO(
        Long id,
        Long examenId,
        Long participationId,
        Long notationId,
        String objet,
        ReclamationStatus statut,
        String reponse,
        Long adjustmentId,
        Long createdByUserId,
        LocalDateTime createdAt,
        Long resolvedByUserId,
        LocalDateTime resolvedAt
) {
    public static ReclamationDTO fromEntity(Reclamation r) {
        if (r == null) return null;
        return new ReclamationDTO(
                r.getId(),
                r.getExamenId(),
                r.getParticipationId(),
                r.getNotationId(),
                r.getObjet(),
                r.getStatut(),
                r.getReponse(),
                r.getAdjustmentId(),
                r.getCreatedByUserId(),
                r.getCreatedAt(),
                r.getResolvedByUserId(),
                r.getResolvedAt());
    }
}
