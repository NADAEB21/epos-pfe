package tn.epos.scoring_service.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
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
 * ADR-0012 : {@code serverNow} est l'horloge serveur (zone {@code app.timezone})
 * estampillée au moment de la requête. L'app évaluateur s'en sert pour corriger
 * le décalage de son horloge locale (offset = serverNow − horloge appareil) et
 * dérouler un compte à rebours précis vers {@code SessionResponse.debutPrevu}
 * entre deux polls, sans jamais refaire le calcul du temps effectif.
 *
 * JSON example :
 * {
 *   "serverNow": "2026-06-20 09:41:07",
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

    // Horloge serveur au moment de la requête (ADR-0012) — référence unique pour
    // le compte à rebours local côté mobile. Même motif que pausedAt/launchedAt.
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime serverNow;

    private List<SessionResponse> sessions;
    private StatsResponse         stats;
    private List<PlanningCellResponse> planning;
}
