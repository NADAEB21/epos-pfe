package tn.epos.scoring_service.dto;

import tn.epos.scoring_service.entities.RotationAssignment;

public record RotationAssignmentDTO(
    Long id,
    Boolean presenceConfirmee,
    Integer tempsAdditionnel,
    Long rotationId,
    Long participationId
) {
    public static RotationAssignmentDTO fromEntity(RotationAssignment a) {
        if (a == null) return null;
        return new RotationAssignmentDTO(
            a.getId(),
            a.getPresenceConfirmee(),
            a.getTempsAdditionnel(),
            (a.getRotation() != null) ? a.getRotation().getId() : null,
            (a.getParticipation() != null) ? a.getParticipation().getId() : null
        );
    }
}