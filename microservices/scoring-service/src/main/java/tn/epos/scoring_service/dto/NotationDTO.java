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
    Long assignmentId,
    // #213 — l'auteur RÉEL, exposé : « qui a noté cet étudiant ? » est la
    // question d'une réclamation, et la réponse ne doit plus être devinée depuis
    // le propriétaire de la station. null = notation antérieure à V15 (inconnu),
    // ce qui est la bonne réponse plutôt qu'un nom inventé.
    Long saisiPar,
    Long verrouillePar
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
            (n.getAssignment() != null) ? n.getAssignment().getId() : null, // Corrected getter
            n.getSaisiPar(),
            n.getVerrouillePar()
        );
    }
}