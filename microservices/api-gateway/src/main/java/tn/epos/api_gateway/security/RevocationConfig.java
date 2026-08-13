package tn.epos.api_gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tn.epos.common.security.revocation.RevocationSyncClient;
import tn.epos.common.security.revocation.TokenRevocationList;

import java.time.Duration;

/**
 * #306 — la copie gateway de la liste de révocation.
 *
 * <p>La gateway est le point de passage de TOUT le trafic HTTP routé : refuser ici coupe un
 * jeton révoqué avant même qu'il n'atteigne un service. Mais elle n'est PAS le point
 * d'application unique — le WebSocket de scoring (port 8083) ne la traverse pas, et les
 * services vérifient donc aussi, chacun avec sa propre copie. Défense en profondeur, trois
 * copies du même instantané.
 *
 * <p>Le poller est bâti sur {@code java.net.http} + un fil démon : rien de bloquant ne
 * s'exécute sur les threads Reactor — le chemin de requête ne fait qu'une lecture de map.
 */
@Configuration
public class RevocationConfig {

    @Bean
    public TokenRevocationList tokenRevocationList() {
        return new TokenRevocationList();
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public RevocationSyncClient revocationSyncClient(
            TokenRevocationList revocationList,
            @Value("${jwt.secret}") String jwtSecret,
            @Value("${epos.auth.base-url:http://auth-service:8081}") String authBaseUrl,
            @Value("${epos.revocation.refresh-ms:30000}") long refreshMs) {
        return new RevocationSyncClient(
                authBaseUrl, jwtSecret, revocationList, Duration.ofMillis(refreshMs));
    }
}
