package tn.epos.scoring_service.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tn.epos.common.security.revocation.TokenRevocationList;

/**
 * #306 — le balayage ne ferme QUE ce que la liste condamne, et compare bien iat à
 * l'estampille (une session RÉ-ouverte après révocation doit survivre au balayage).
 */
class WebSocketRevocationSweepTest {

    private static final Instant STAMP = Instant.parse("2026-08-13T10:00:00Z");

    private final WebSocketSessionRegistry registry = new WebSocketSessionRegistry();
    private final TokenRevocationList list = new TokenRevocationList();
    private final WebSocketRevocationSweep sweep = new WebSocketRevocationSweep(registry, list);

    private WebSocketSession session(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        return s;
    }

    @Test
    @DisplayName("session d'un utilisateur révoqué (jeton antérieur) → fermée en 1008")
    void revoqueFerme() throws IOException {
        WebSocketSession s = session("s1");
        registry.register(s);
        registry.authenticate("s1", 60L, STAMP.minusSeconds(10));
        list.replaceAll(Map.of(60L, STAMP));

        sweep.sweep();

        verify(s).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("session RÉ-ouverte après la révocation (iat postérieur) → survit")
    void reconnexionPosterieureSurvit() throws IOException {
        WebSocketSession s = session("s2");
        registry.register(s);
        registry.authenticate("s2", 60L, STAMP.plusSeconds(10));
        list.replaceAll(Map.of(60L, STAMP));

        sweep.sweep();

        verify(s, never()).close(org.mockito.ArgumentMatchers.any(CloseStatus.class));
    }

    @Test
    @DisplayName("utilisateur non révoqué → rien ne bouge")
    void nonRevoqueSurvit() throws IOException {
        WebSocketSession s = session("s3");
        registry.register(s);
        registry.authenticate("s3", 7L, STAMP.minusSeconds(10));
        list.replaceAll(Map.of(60L, STAMP));

        sweep.sweep();

        verify(s, never()).close(org.mockito.ArgumentMatchers.any(CloseStatus.class));
    }

    @Test
    @DisplayName("une session fermée entre-temps ne casse pas le balayage des suivantes")
    void fermetureEnEchecNInterromptPas() throws IOException {
        WebSocketSession morte = session("s4");
        org.mockito.Mockito.doThrow(new IOException("déjà fermée")).when(morte)
                .close(org.mockito.ArgumentMatchers.any(CloseStatus.class));
        WebSocketSession vivante = session("s5");
        registry.register(morte);
        registry.register(vivante);
        registry.authenticate("s4", 60L, STAMP.minusSeconds(10));
        registry.authenticate("s5", 61L, STAMP.minusSeconds(10));
        list.replaceAll(Map.of(60L, STAMP, 61L, STAMP));

        sweep.sweep();

        verify(vivante).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("unregister retire la session ET son identité — plus rien à balayer")
    void unregisterNettoieTout() throws IOException {
        WebSocketSession s = session("s6");
        registry.register(s);
        registry.authenticate("s6", 60L, STAMP.minusSeconds(10));
        registry.unregister("s6");
        list.replaceAll(Map.of(60L, STAMP));

        sweep.sweep();

        verify(s, never()).close(org.mockito.ArgumentMatchers.any(CloseStatus.class));
    }
}
