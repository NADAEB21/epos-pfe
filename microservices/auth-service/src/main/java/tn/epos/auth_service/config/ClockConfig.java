package tn.epos.auth_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * #294 — l'horloge devient injectable, comme dans exam-service et
 * scoring-service (précédent ADR-0010).
 *
 * <p>Le verrou temporaire est une décision datée : « ce compte rouvre à telle
 * heure ». Sans horloge injectable, aucun test ne peut prouver qu'il s'ouvre
 * effectivement — il faudrait attendre. Les tests substituent une horloge fixe
 * et avancent le temps ; la production garde l'horloge système.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
