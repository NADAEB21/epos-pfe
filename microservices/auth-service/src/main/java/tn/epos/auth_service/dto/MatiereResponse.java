package tn.epos.auth_service.dto;

import tn.epos.auth_service.entity.Matiere;

public record MatiereResponse(Long id, String code, String libelle) {

    public static MatiereResponse from(Matiere m) {
        return new MatiereResponse(m.getId(), m.getCode(), m.getLibelle());
    }
}
