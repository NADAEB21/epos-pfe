package tn.epos.scoring_service.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.HandshakeInterceptor;
import tn.epos.scoring_service.websocket.StompConnectAuthenticator;
import tn.epos.scoring_service.websocket.WebSocketSessionRegistry;

import java.util.Map;

/**
 * BF6.1 — Configuration du broker de messages WebSocket STOMP.
 *
 * <p>Architecture de messagerie :
 * <ul>
 *   <li>{@code /ws} — endpoint SockJS que les clients (Flutter, Angular)
 *       utilisent pour établir la connexion WebSocket avec fallback HTTP.</li>
 *   <li>{@code /topic/**} — broker in-memory pour les topics de diffusion
 *       (scores temps réel, statut des lots).</li>
 *   <li>{@code /app} — préfixe pour les messages entrants (si besoin futur
 *       de messages client → serveur via {@code @MessageMapping}).</li>
 * </ul>
 *
 * <p>Topics utilisés par le scoring-service :
 * <ul>
 *   <li>{@code /topic/stations/{stationId}/scores} — mise à jour du score
 *       d'un étudiant après chaque saisie, consommé par l'app Flutter.</li>
 *   <li>{@code /topic/lots/{lotId}/status} — changement de statut d'un lot
 *       (EN_COURS → TERMINE), consommé par l'app Flutter et le dashboard.</li>
 *   <li>{@code /topic/examens/{examenId}/dashboard} — rafraîchissement global
 *       du tableau de bord, consommé par le dashboard Angular (BF5.1).</li>
 * </ul>
 *
 * <p><b>Sécurité :</b> l'authentification des connexions WebSocket est gérée
 * dans {@link WebSocketSecurityConfig} via le JWT fourni dans les headers
 * STOMP CONNECT. Le gateway Spring Cloud ne route pas le WebSocket — le
 * client se connecte directement au scoring-service sur son port interne.
 * En production, le reverse proxy (nginx) gère le TLS et le forwarding.
 */
@Configuration
@EnableWebSocketMessageBroker
// #306 — le balayage de révocation des sessions ouvertes est planifié (30 s).
@EnableScheduling
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketSessionRegistry sessionRegistry;

    /**
     * Configure le broker de messages in-memory.
     *
     * <p>Un broker externe (RabbitMQ / ActiveMQ) peut remplacer le broker
     * in-memory en phase de montée en charge, sans modifier les clients.
     * Tracké sous ADR futur « WebSocket broker scalability ».
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Destinations pour les messages sortants (serveur → clients)
        registry.enableSimpleBroker("/topic");

        // Préfixe pour les messages entrants (clients → serveur)
        // Utilisé par @MessageMapping si on ajoute des actions côté serveur
        registry.setApplicationDestinationPrefixes("/app");
    }

    /**
     * Enregistre l'endpoint SockJS.
     *
     * <p>SockJS offre un fallback HTTP long-polling pour les environnements
     * qui bloquent les WebSockets (certains réseaux Wi-Fi d'établissements).
     * L'app Flutter utilise {@code stomp_dart_client} avec SockJS.
     *
     * <p>CORS : autorisé pour toutes les origines en développement.
     * Restreindre à l'origine de production (variables d'environnement)
     * lors du déploiement final.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                // Tous les clients autorisés (Gateway + app mobile)
                // En production : restreindre à l'IP du gateway
                .setAllowedOriginPatterns("*")
                // #306 — capture l'Authorization de la poignée de main HTTP dans les
                // attributs de session : les APK déjà installées n'envoient leur jeton
                // QUE là (webSocketConnectHeaders), jamais dans la frame CONNECT.
                // StompConnectAuthenticator s'en sert comme seconde source.
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest request,
                                                   ServerHttpResponse response,
                                                   WebSocketHandler wsHandler,
                                                   Map<String, Object> attributes) {
                        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                        if (authorization != null) {
                            attributes.put(StompConnectAuthenticator.HANDSHAKE_AUTH_ATTRIBUTE,
                                    authorization);
                        }
                        return true;
                    }

                    @Override
                    public void afterHandshake(ServerHttpRequest request,
                                               ServerHttpResponse response,
                                               WebSocketHandler wsHandler, Exception exception) {
                        // rien — la capture a lieu avant.
                    }
                })
                .withSockJS();
    }

    /**
     * #306 — chaque connexion physique est enregistrée à l'établissement et retirée à la
     * fermeture : c'est ce qui donne au balayage une prise sur les sessions OUVERTES.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessionRegistry.register(session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus)
                    throws Exception {
                sessionRegistry.unregister(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        });
    }
}
