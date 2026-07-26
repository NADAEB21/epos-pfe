package tn.epos.scoring_service.dto;

/**
 * #185 — résultat de « Présence & démarrer » : la présence ET la génération d'un lot,
 * exécutées en un seul acte transactionnel ({@code LotDemarrageService}).
 */
public record DemarrageResult(
        Long lotId,
        int presents,
        int absents,
        int rotations,
        int assignments,
        String avertissement) {
}
