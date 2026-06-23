package tn.epos.scoring_service.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Réponse complète de GET /api/evaluateur/dashboard.
 *
 * Correspond exactement à ce que SessionBloc Flutter attend pour
 * construire l'état SessionLoaded :
 *   - sessions     → SessionBloc.getSessions()
 *   - stats        → SessionBloc.getStats()
 *   - planning     → SessionBloc.getPlanningDuJour()
 *
 * JSON example :
 * {
 *   "sessions": [...],
 *   "stats": { "sessionsAssignees": 3, "totalEtudiants": 24, ... },
 *   "planning": [...]
 * }
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluateurDashboardResponse {

    private List<SessionResponse> sessions;
    private StatsResponse         stats;
    private List<PlanningCellResponse> planning;
}
