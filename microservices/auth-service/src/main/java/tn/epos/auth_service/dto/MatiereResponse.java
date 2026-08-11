package tn.epos.auth_service.dto;

import tn.epos.auth_service.entity.Matiere;

import java.time.LocalDateTime;

/**
 * #134 — porte aussi l'état du catalogue. {@code active} = false signifie
 * « matière retirée » : les pickers (création d'examen, nomination d'un
 * responsable) doivent l'exclure, mais la liste reste COMPLÈTE côté serveur —
 * les libellés des examens et rôles historiques en dépendent (sans quoi
 * l'écran retombe sur « Matière 7 »).
 */
public record MatiereResponse(
        Long id,
        String code,
        String libelle,
        boolean active,
        LocalDateTime retiredAt,
        Long retiredBy,
        String retirementMotif
) {

    public static MatiereResponse from(Matiere m) {
        return new MatiereResponse(
                m.getId(),
                m.getCode(),
                m.getLibelle(),
                !Boolean.FALSE.equals(m.getActive()),
                m.getRetiredAt(),
                m.getRetiredBy(),
                m.getRetirementMotif());
    }
}
