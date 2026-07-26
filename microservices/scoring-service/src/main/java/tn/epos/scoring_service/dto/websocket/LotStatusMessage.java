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
 *   <li><s>Dashboard Angular</s> — <b>PAS de consommateur web à ce jour</b> (vérifié
 *       2026-07-21) : {@code frontend-web} n'a aucune dépendance STOMP/SockJS et aucune
 *       référence à {@code /topic/} dans {@code src}. L'alerte « lot terminé » du
 *       responsable (#208) se dérive donc de la lecture REST, pas de ce message —
 *       cf. ADR-0014-B §5. Ne pas re-planifier #208 en supposant ce canal disponible.</li>
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

    /**
     * Destination STOMP, à formater avec le {@code lotId}. Définie ICI et pas dans
     * chaque service émetteur : deux copies d'une même destination « qui doivent rester
     * identiques » ont déjà divergé ailleurs dans ce service (cf. le doublon
     * {@code NotationReajustementService} / {@code EvaluateurDashboardService}).
     * Le client Flutter en tient le miroir — {@code api_constants.dart:110}.
     */
    public static final String TOPIC = "/topic/lots/%d/status";

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