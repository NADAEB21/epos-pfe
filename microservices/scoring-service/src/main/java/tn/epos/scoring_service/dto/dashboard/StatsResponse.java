package tn.epos.scoring_service.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Statistiques du tableau de bord évaluateur.
 *
 * Correspond à EvaluateurStatsModel.fromJson() dans Flutter :
 *   json['sessionsAssignees'] → int
 *   json['totalEtudiants']    → int
 *   json['lotsValides']       → int
 *   json['totalLots']         → int
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatsResponse {

    private int sessionsAssignees;
    private int totalEtudiants;
    private int lotsValides;
    private int totalLots;
}