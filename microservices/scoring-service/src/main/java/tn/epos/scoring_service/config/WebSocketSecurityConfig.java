package tn.epos.scoring_service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import tn.epos.common.security.ScopedAuthoritiesConverter;
import tn.epos.scoring_service.websocket.StompConnectAuthenticator;
import tn.epos.scoring_service.websocket.WebSocketSessionRegistry;

/**
 * BF6.1 + #306 — sécurisation des connexions WebSocket STOMP par JWT, désormais FERMÉE.
 *
 * <p>La logique vit dans {@link StompConnectAuthenticator} (classe nommée, testée) ; cette
 * configuration ne fait que le brancher. Deux changements de fond par rapport à BF6.1 :
 * <ul>
 *   <li>⛔ <b>le repli « connexion anonyme » a été supprimé</b> — il laissait entrer TOUTES les
 *       connexions mobiles (le jeton n'était jamais dans la frame CONNECT, seulement dans la
 *       poignée de main HTTP, que l'ancien code ne lisait pas). Sans identité, la révocation
 *       (#306) n'aurait eu aucune prise sur les sessions ouvertes ;</li>
 *   <li>le décodeur injecté est le {@code RevocationAwareJwtDecoder} du service : un jeton
 *       révoqué est refusé À la connexion, et {@link tn.epos.scoring_service.websocket.WebSocketRevocationSweep}
 *       débranche celles déjà établies.</li>
 * </ul>
 *
 * <p><b>Note d'architecture (inchangée) :</b> Spring Security WebSocket natif exige une session
 * HTTP partagée, incompatible avec le mode stateless JWT + SockJS — d'où l'intercepteur de
 * canal personnalisé.
 */
@Configuration
public class WebSocketSecurityConfig implements WebSocketMessageBrokerConfigurer {

    private final StompConnectAuthenticator authenticator;

    public WebSocketSecurityConfig(JwtDecoder jwtDecoder, WebSocketSessionRegistry registry) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new ScopedAuthoritiesConverter());
        this.authenticator = new StompConnectAuthenticator(jwtDecoder, converter, registry);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authenticator);
    }
}
