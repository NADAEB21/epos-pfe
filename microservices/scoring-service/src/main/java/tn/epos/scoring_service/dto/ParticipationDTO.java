package tn.epos.scoring_service.dto;

import tn.epos.scoring_service.entities.ExamenParticipation;

public record ParticipationDTO(
    Long id,
    Long examen_id,
    String num_echantillon,
    Float note,
    Boolean est_present,
    Object etudiant, // Keep as Object or specific Student DTO if available
    Object lot       // Keep as Object or specific Lot DTO if available
) {
    public static ParticipationDTO fromEntity(ExamenParticipation p) {
        if (p == null) return null;
        return new ParticipationDTO(
            p.getId(),
            p.getExamen_id(),
            p.getNum_echantillon(),
            p.getNote(),
            p.getEst_present(),
            p.getEtudiant(),
            p.getLot()
        );
    }
}