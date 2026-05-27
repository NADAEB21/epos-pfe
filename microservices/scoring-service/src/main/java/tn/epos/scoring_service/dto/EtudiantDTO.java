package tn.epos.scoring_service.dto;

import tn.epos.scoring_service.entities.Etudiant;

public record EtudiantDTO(
    Long id,
    String nom,
    String prenom,
    String numero_inscription
) {
    public static EtudiantDTO fromEntity(Etudiant e) {
        if (e == null) return null;
        return new EtudiantDTO(
            e.getId(),
            e.getNom(),
            e.getPrenom(),
            e.getNumero_inscription()
        );
    }
}