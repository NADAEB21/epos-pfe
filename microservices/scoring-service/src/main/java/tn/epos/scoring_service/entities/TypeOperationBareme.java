package tn.epos.scoring_service.entities;

/**
 * Les trois opérations permises sur un barème de délibération — ADR-0021 D8,
 * reprises telles quelles par ADR-0030 D2. Énumération FERMÉE : le type n'est
 * pas un champ libre, aucune autre opération n'existe (le CHECK de V25 verrouille
 * la même liste côté base).
 *
 * <ul>
 *   <li>{@link #EXCLURE_CRITERE} — la contribution du critère sort de la somme,
 *       son maximum sort du dénominateur (cible : {@code exam_item_snapshot.item_id}).</li>
 *   <li>{@link #EXCLURE_STATION} — la station entière sort des deux sommes
 *       (cible : {@code exam_grille_snapshot.station_id}).</li>
 *   <li>{@link #REPONDERER} — re-mise à l'échelle proportionnelle de la
 *       performance vers {@code nouvelle_echelle} (critère ou station) ; le
 *       simple déplacement de budget est un no-op, découverte centrale
 *       d'ADR-0021 Part 2.</li>
 * </ul>
 */
public enum TypeOperationBareme {
    EXCLURE_CRITERE,
    EXCLURE_STATION,
    REPONDERER
}
