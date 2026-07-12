package tn.epos.scoring_service.dto.websocket;

import lombok.Builder;
import lombok.Getter;

/**
 * BF6.1 — Message WebSocket diffusé sur {@code /topic/lots/{lotId}/status}
 * lors d'un changement de statut d'un lot (validation, fin de rotation).
 *
 * <p>Consommateurs :
 * <ul>
 *   <li>App Flutter ({@code WebSocketService.onLotStatusUpdate}) — met à jour
 *       {@code GradingLoaded.lotValide} en temps réel, ce qui désactive les
 *       boutons de saisie et affiche le récapitulatif.</li>
 *   <li>Dashboard Angular — met à jour l'indicateur de progression par station
 *       dans le tableau de supervision (BF5.1).</li>
 * </ul>
 *
 * <p>Format JSON émis :
 * <pre>{@code
 * {
 *   "lotId"        : 12,
 *   "examenId"     : 5,
 *   "statut"       : "TERMINE",
 *   "numeroLot"    : 3,
 *   "totalLots"    : 8
 * }
 * }</pre>
 */
@Getter
@Builder
public class LotStatusMessage {

    /** Identifiant du lot dont le statut a changé. */
    private final Long   lotId;

    /** Identifiant de l'examen parent (permet au dashboard de filtrer). */
    private final Long   examenId;

    /**
     * Nouveau statut du lot.
     * Valeurs possibles : {@code "EN_COURS"}, {@code "TERMINE"}.
     */
    private final String statut;

    /** Numéro du lot dans la séquence (ex : 3). */
    private final Integer numeroLot;

    /** Nombre total de lots de l'examen (ex : 8). */
    private final Integer totalLots;
}