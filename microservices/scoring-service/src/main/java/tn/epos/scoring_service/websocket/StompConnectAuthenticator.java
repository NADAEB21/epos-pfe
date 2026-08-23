package tn.epos.scoring_service.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.Map;

/**
 * #306 — l'authentification du CONNECT STOMP, extraite en classe nommée et FERMÉE.
 *
 * <p><b>Ce qui change par rapport à BF6.1, et pourquoi.</b>
 * <ol>
 *   <li><b>Le repli anonyme disparaît.</b> L'ancien intercepteur laissait passer un CONNECT
 *       sans jeton (« connexion anonyme ») — et comme l'app mobile n'envoyait son jeton que
 *       dans la poignée de main HTTP, jamais dans la frame CONNECT, TOUTES les connexions
 *       mobiles étaient en réalité anonymes : l'authentification « BF6.1 » n'authentifiait
 *       personne. Un balayage de révocation sans identité n'aurait rien à balayer — le
 *       fail-open devait tomber pour que #306 existe.</li>
 *   <li><b>Deux sources de jeton, dans cet ordre :</b> la frame CONNECT (les clients à jour),
 *       puis l'en-tête {@code Authorization} de la poignée de main HTTP, capturé dans les
 *       attributs de session par {@code WebSocketConfig} — c'est ce qui garde compatibles les
 *       APK déjà installées, qui n'envoient que celui-là (leçon #314 : une APK plus vieille
 *       que le backend est un scénario réel, pas un cas d'école).</li>
 *   <li>Le décodeur injecté est le {@code RevocationAwareJwtDecoder} du service : un jeton
 *       révoqué est refusé À la connexion, par le même chemin que le REST.</li>
 * </ol>
 *
 * <p>Le web n'est pas concerné : {@code frontend-web} n'a aucun client STOMP (vérifié —
 * le Suivi interroge par intervalle, {@code suivi.component.ts:427}).
 */
@Slf4j
public class StompConnectAuthenticator implements ChannelInterceptor {

    /** Clé d'attribut posée par l'intercepteur de poignée de main de {@code WebSocketConfig}. */
    public static final String HANDSHAKE_AUTH_ATTRIBUTE = "epos.handshake.authorization";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthConverter;
    private final WebSocketSessionRegistry registry;

    public StompConnectAuthenticator(JwtDecoder jwtDecoder,
                                     JwtAuthenticationConverter jwtAuthConverter,
                                     WebSocketSessionRegistry registry) {
        this.jwtDecoder = jwtDecoder;
        this.jwtAuthConverter = jwtAuthConverter;
        this.registry = registry;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String bearer = firstNonNull(
                accessor.getFirstNativeHeader("Authorization"),
                handshakeAuthorization(accessor));

        if (bearer == null || !bearer.startsWith(BEARER_PREFIX)) {
            // FERMÉ : pas de jeton, pas de connexion. L'AccessDeniedException traverse en
            // MessageDeliveryException côté client — la frame ERROR ferme la session.
            throw new AccessDeniedException(
                    "Connexion WebSocket refusée : aucun jeton présenté (#306).");
        }

        try {
            Jwt jwt = jwtDecoder.decode(bearer.substring(BEARER_PREFIX.length()));
            Authentication auth = (AbstractAuthenticationToken) jwtAuthConverter.convert(jwt);
            accessor.setUser(auth);

            Object userId = jwt.getClaim("userId");
            if (userId instanceof Number n && accessor.getSessionId() != null) {
                registry.authenticate(accessor.getSessionId(), n.longValue(), jwt.getIssuedAt());
            }
            return message;
        } catch (Exception e) {
            // Jeton invalide, expiré OU révoqué (RevocationAwareJwtDecoder) : même refus.
            throw new AccessDeniedException(
                    "Connexion WebSocket refusée : jeton invalide ou révoqué (#306).", e);
        }
    }

    private String handshakeAuthorization(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        return attributes == null ? null : (String) attributes.get(HANDSHAKE_AUTH_ATTRIBUTE);
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
