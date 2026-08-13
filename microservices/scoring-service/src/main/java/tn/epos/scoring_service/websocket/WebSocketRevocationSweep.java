package tn.epos.scoring_service.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tn.epos.common.security.revocation.TokenRevocationList;

/**
 * #306 — le balayage : ce que la liste de révocation condamne, on le débranche.
 *
 * <p>C'est LA raison pour laquelle l'option D a gagné dans l'analyse du ticket : un mécanisme
 * périodique n'a pas besoin d'une « requête suivante » pour agir — il atteint donc une
 * connexion WebSocket ouverte, ce qu'aucune vérification par requête ne fera jamais (une
 * connexion établie ne refait aucune requête HTTP).
 *
 * <p>Même cadence que la synchronisation de la liste (30 s par défaut) : balayer plus vite
 * n'apprendrait rien de plus — l'information n'arrive qu'au rythme du poller.
 */
@Component
@RequiredArgsConstructor
public class WebSocketRevocationSweep {

    private final WebSocketSessionRegistry registry;
    private final TokenRevocationList revocationList;

    @Scheduled(fixedDelayString = "${epos.revocation.refresh-ms:30000}")
    public void sweep() {
        for (WebSocketSessionRegistry.SessionAuthentifiee s : registry.authenticatedSessions()) {
            if (revocationList.isRevoked(s.userId(), s.issuedAt())) {
                registry.close(s, "jeton révoqué (#306)");
            }
        }
    }
}
