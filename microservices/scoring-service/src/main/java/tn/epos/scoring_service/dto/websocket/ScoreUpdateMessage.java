package tn.epos.scoring_service.dto.websocket;

import lombok.Builder;
import lombok.Getter;

/**
 * BF6.1 — Message WebSocket diffusé sur {@code /topic/stations/{stationId}/scores}
 * après chaque saisie de notation par un évaluateur.
 *
 * <p>Consommateurs :
 * <ul>
 *   <li>App Flutter ({@code WebSocketService.onScoreUpdate}) — met à jour le score
 *       affiché en temps réel dans la grille de notation ({@code GradingLoaded.wsScores}).</li>
 *   <li>Dashboard Angular — rafraîchit la colonne de l'étudiant dans le tableau
 *       de supervision (BF5.1).</li>
 * </ul>
 *
 * <p>Champs intentionnellement minimaux (pseudonymisation) : seul {@code etudiantId}
 * est transmis, jamais le nom. Le client résout le nom depuis son état local.
 *
 * <p>Format JSON émis :
 * <pre>{@code
 * {
 *   "etudiantId" : 101,
 *   "stationId"  : 3,
 *   "grilleId"   : 1,
 *   "score"      : 14.0,
 *   "verrouille" : false
 * }
 * }</pre>
 */
@Getter
@Builder
public class ScoreUpdateMessage {

    /** Identifiant de l'étudiant concerné. */
    private final Long    etudiantId;

    /** Station sur laquelle le score a été mis à jour. */
    private final Long    stationId;

    /** Grille d'évaluation utilisée (pour que le client calcule /noteMax). */
    private final Long    grilleId;

    /**
     * Score recalculé côté serveur après la saisie.
     * Calculé par {@code EvaluateurDashboardService.recalculerScoreFinal()}
     * avec les pondérations de l'exam-service — identique à la formule
     * {@code ScoreUtils.calculerScore()} côté Flutter.
     */
    private final Float   score;

    /**
     * {@code true} si la notation a été verrouillée (validerEtudiant).
     * Permet au client de désactiver la grille de saisie immédiatement.
     */
    private final Boolean verrouille;
}
