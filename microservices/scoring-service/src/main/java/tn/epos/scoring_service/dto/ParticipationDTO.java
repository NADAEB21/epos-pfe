package tn.epos.scoring_service.dto;

import tn.epos.scoring_service.entities.ExamenParticipation;

public record ParticipationDTO(
    Long id,
    Long examen_id,
    String num_echantillon,
    Float note,
    Boolean est_present,
    Long etudiantId, // Changed from Object to Long ID
    Long lotId      // Changed from Object to Long ID
) {
    public static ParticipationDTO fromEntity(ExamenParticipation p) {
        if (p == null) return null;
        return new ParticipationDTO(
            p.getId(),
            p.getExamen_id(),
            p.getNum_echantillon(),
            p.getNote(),
            p.getEst_present(),
            p.getEtudiant() != null ? p.getEtudiant().getId() : null,
            p.getLot() != null ? p.getLot().getId() : null
        );
    }
}