package tn.epos.scoring_service.dto;

import tn.epos.scoring_service.entities.Notation;

public record NotationDTO(
    Long id,
    Float score_final,
    Integer temps_additionnel,
    Boolean is_synced,
    Boolean verouillee,
    Long stationId,
    Long grilleId,
    Long assignmentId       
) {
    public static NotationDTO fromEntity(Notation n) {
        if (n == null) return null;
        return new NotationDTO(
            n.getId(),
            n.getScore_final(), 
            n.getTemps_additionnel(),
            n.getIs_synced(),
            n.getVerouillee(),
            n.getStationId(),
            n.getGrilleId(),
            (n.getAssignment() != null) ? n.getAssignment().getId() : null // Corrected getter
        );
    }
}