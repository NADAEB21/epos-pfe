package tn.epos.scoring_service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

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
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

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
                .withSockJS();
    }
}
