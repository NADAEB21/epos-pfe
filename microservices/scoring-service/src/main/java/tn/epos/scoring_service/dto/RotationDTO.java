package tn.epos.scoring_service.dto;

import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationStatus;
import java.time.LocalDateTime;

public record RotationDTO(
    Long id,
    Long evaluateurId,
    Long stationId,
    Integer ordrePassage,
    LocalDateTime debutCreneau,
    RotationStatus statut,
    Long studentGroupId
) {
    public static RotationDTO fromEntity(Rotation r) {
        if (r == null) return null;
        return new RotationDTO(
            r.getId(),
            r.getEvaluateurId(),
            r.getStationId(),
            r.getOrdrePassage(),
            r.getDebutCreneau(),
            r.getStatut(),
            (r.getStudentGroup() != null) ? r.getStudentGroup().getId() : null
        );
    }
}