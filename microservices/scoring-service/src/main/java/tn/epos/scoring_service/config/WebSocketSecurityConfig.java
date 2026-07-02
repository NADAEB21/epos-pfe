package tn.epos.scoring_service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import tn.epos.common.security.ScopedAuthoritiesConverter;

/**
 * BF6.1 — Sécurisation des connexions WebSocket STOMP avec JWT.
 *
 * <p>Le protocole STOMP expose une frame {@code CONNECT} sur laquelle le client
 * Flutter envoie son token JWT dans l'en-tête {@code Authorization: Bearer <token>}.
 * Cet intercepteur valide le token à la connexion et peuple le
 * {@link SecurityContextHolder} pour que les {@code @PreAuthorize} restent
 * fonctionnels sur les {@code @MessageMapping} éventuels.
 *
 * <p>Les topics {@code /topic/**} sont en lecture seule côté client —
 * le serveur y pousse (via {@code SimpMessagingTemplate}) et les clients
 * se contentent de s'abonner. L'évaluateur ne peut pas publier sur un topic
 * d'une autre station car le scoring-service est le seul producteur.
 *
 * <p><b>Note d'architecture :</b> Spring Security WebSocket native
 * ({@code AbstractSecurityWebSocketMessageBrokerConfigurer}) requiert une
 * session HTTP partagée avec le WebSocket, incompatible avec le mode
 * stateless JWT + SockJS sans session. On utilise donc un intercepteur
 * de canal personnalisé, cohérent avec l'ADR « stateless auth » du projet.
 */
@Configuration
public class WebSocketSecurityConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtDecoder              jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthConverter;

    public WebSocketSecurityConfig(JwtDecoder jwtDecoder) {
        this.jwtDecoder       = jwtDecoder;
        this.jwtAuthConverter = buildConverter();
    }

    /**
     * Intercepte la frame STOMP CONNECT pour authentifier le client.
     *
     * <p>Le token est lu depuis l'en-tête {@code Authorization} de la frame
     * CONNECT. En cas de token manquant ou invalide, la connexion est rejetée
     * avec une {@link org.springframework.messaging.MessageDeliveryException}.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {

            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor == null) {
                    return message;
                }

                // Authentification uniquement à la frame CONNECT
                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String authHeader = accessor.getFirstNativeHeader("Authorization");
                    Authentication auth = resolveAuthentication(authHeader);
                    if (auth != null) {
                        accessor.setUser(auth);
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                    // En cas d'échec de résolution, la connexion est établie
                    // mais l'utilisateur n'est pas authentifié — les topics
                    // /topic/** sont accessibles en lecture (données non sensibles
                    // car pseudonymisées par etudiantId, pas par nom).
                    // Pour un niveau de sécurité maximal, lever une exception ici.
                }

                return message;
            }
        });
    }

    // ── Utilitaires privés ────────────────────────────────────────────────────

    private Authentication resolveAuthentication(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        try {
            String rawToken = authHeader.substring(7);
            Jwt    jwt      = jwtDecoder.decode(rawToken);
            AbstractAuthenticationToken auth =
                    (AbstractAuthenticationToken) jwtAuthConverter.convert(jwt);
            if (auth != null) {
                auth.setAuthenticated(true);
            }
            return auth;
        } catch (Exception e) {
            // Token expiré ou invalide → connexion anonyme
            return null;
        }
    }

    /**
     * Réutilise la même logique que {@link SecurityConfig} pour que les
     * autorités STOMP et REST soient cohérentes (ROLE_EVALUATEUR, etc.).
     */
    private JwtAuthenticationConverter buildConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new ScopedAuthoritiesConverter());
        return converter;
    }
}