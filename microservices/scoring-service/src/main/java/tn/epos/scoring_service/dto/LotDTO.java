package tn.epos.scoring_service.dto;

import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.entities.LotStatus;

import java.time.LocalDate;

public record LotDTO(
    Long id,
    Integer numeroLot,
    Integer tailleLot,
    LotStatus statut,
    Long evaluateurId,
    Long examenId,
    LocalDate jour   // ADR-0014-A §5 / #147 — null = jour unique de l'examen
) {
    public static LotDTO fromEntity(Lot lot) {
        if (lot == null) return null;
        return new LotDTO(
            lot.getId(),
            lot.getNumeroLot(),
            lot.getTailleLot(),
            lot.getStatut(),
            lot.getEvaluateurId(),
            lot.getExamenId(),
            lot.getJour()
        );
    }
}