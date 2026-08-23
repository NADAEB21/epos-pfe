package tn.epos.auth_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import tn.epos.common.security.revocation.TokenRevocationList;

/**
 * #306 — la copie locale de la liste de révocation d'auth-service.
 *
 * <p>Contrairement aux autres services, elle n'est PAS nourrie par HTTP : auth est
 * l'émetteur des révocations. Elle est alimentée en direct par
 * {@link tn.epos.auth_service.service.TokenRevocationService} (synchrone à l'acte,
 * plus relecture périodique de la base — d'où {@link EnableScheduling}).
 */
@Configuration
@EnableScheduling
public class RevocationConfig {

    @Bean
    public TokenRevocationList tokenRevocationList() {
        return new TokenRevocationList();
    }
}
