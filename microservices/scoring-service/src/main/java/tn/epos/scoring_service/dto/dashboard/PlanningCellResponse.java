package tn.epos.scoring_service.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Cellule du planning du jour (heure × lot).
 *
 * Correspond à PlanningCellModel.fromJson() dans Flutter :
 *   json['heure']     → String  (ex: "09:00")
 *   json['lotNumero'] → int
 *   json['statut']    → String  ("TERMINE" | "A_VENIR" | "AUCUN")
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanningCellResponse {

    private String heure;       // format "HH:mm"
    private int    lotNumero;
    private String statut;      // "TERMINE" | "A_VENIR" | "AUCUN"
    /** Numéro du groupe (1..K) qui passe à cette heure, dans ce lot — pour
     *  la carte de détail "Lot en cours" côté mobile. Null pour un backend
     *  antérieur ou une rotation sans groupe résolu. */
    private Integer groupeNumero;
}