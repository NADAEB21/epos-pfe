package tn.epos.scoring_service.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * #306 — l'annuaire des connexions WebSocket OUVERTES, avec l'identité prouvée au CONNECT.
 *
 * <p><b>Pourquoi il existe.</b> Une connexion STOMP montre son jeton UNE fois, à la poignée de
 * main, puis vit des heures : c'est le seul canal du système qu'une révocation ne pouvait
 * jamais atteindre (constat du ticket #306). Le décorateur de transport enregistre chaque
 * session physique ; l'authentificateur du CONNECT y attache (userId, iat) ; le balayage
 * périodique ({@link WebSocketRevocationSweep}) ferme ce que la liste de révocation condamne.
 *
 * <p>Le nettoyage est du ressort du décorateur ({@code afterConnectionClosed}) — aucune
 * entrée ne survit à sa connexion.
 */
@Component
@Slf4j
public class WebSocketSessionRegistry {

    /** Une session authentifiée : la connexion physique + qui, prouvé quand. */
    public record SessionAuthentifiee(WebSocketSession session, Long userId, Instant issuedAt) {
    }

    private final Map<String, WebSocketSession> sessionsParId = new ConcurrentHashMap<>();
    private final Map<String, SessionAuthentifiee> identitesParId = new ConcurrentHashMap<>();

    /** Transport établi — appelé par le décorateur, AVANT le CONNECT STOMP. */
    public void register(WebSocketSession session) {
        sessionsParId.put(session.getId(), session);
    }

    /** Transport fermé — la session et son identité disparaissent ensemble. */
    public void unregister(String sessionId) {
        sessionsParId.remove(sessionId);
        identitesParId.remove(sessionId);
    }

    /**
     * CONNECT authentifié — attache l'identité à la session physique. Si le transport n'est
     * pas (encore) connu sous cet id, l'identité est retenue quand même : le balayage ne
     * pourra pas fermer la connexion mais saura qu'elle existe.
     */
    public void authenticate(String sessionId, Long userId, Instant issuedAt) {
        identitesParId.put(sessionId,
                new SessionAuthentifiee(sessionsParId.get(sessionId), userId, issuedAt));
    }

    /** Instantané des sessions authentifiées — pour le balayage. */
    public List<SessionAuthentifiee> authenticatedSessions() {
        return new ArrayList<>(identitesParId.values());
    }

    public int openCount() {
        return sessionsParId.size();
    }

    /**
     * Ferme une session au nom d'une révocation. 1008 (POLICY_VIOLATION) : le client mobile
     * traite toute fermeture par sa reconnexion à backoff — la reconnexion re-présentera le
     * jeton, et un jeton révoqué sera refusé au CONNECT. La boucle est fermée.
     */
    public void close(SessionAuthentifiee s, String pourquoi) {
        if (s.session() == null) {
            return;
        }
        try {
            s.session().close(CloseStatus.POLICY_VIOLATION);
            log.info("#306 : session WebSocket {} fermée — {} (utilisateur {})",
                    s.session().getId(), pourquoi, s.userId());
        } catch (IOException e) {
            // La session peut être déjà morte côté transport : le décorateur la retirera.
            log.warn("#306 : fermeture de la session {} impossible ({}) — elle sera retirée "
                    + "au prochain afterConnectionClosed", s.session().getId(), e.getMessage());
        }
    }
}
