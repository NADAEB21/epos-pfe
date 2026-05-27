package tn.epos.scoring_service.dto;

import tn.epos.scoring_service.entities.RotationAssignment;

public record RotationAssignmentDTO(
    Long id,
    Boolean presenceConfirmee,
    Integer tempsAdditionnel,
    Object rotation,    // Keep as Object or specific Rotation DTO
    Object participation // Keep as Object or specific Participation DTO
) {
    public static RotationAssignmentDTO fromEntity(RotationAssignment a) {
        if (a == null) return null;
        return new RotationAssignmentDTO(
            a.getId(),
            a.getPresenceConfirmee(),
            a.getTempsAdditionnel(),
            a.getRotation(),
            a.getParticipation()
        );
    }
}