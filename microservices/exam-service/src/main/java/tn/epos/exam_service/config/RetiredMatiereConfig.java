package tn.epos.exam_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tn.epos.exam_service.catalogue.RetiredMatiereList;
import tn.epos.exam_service.catalogue.RetiredMatiereSyncClient;

import java.time.Duration;

/**
 * #303 — câblage de la copie locale des matières retirées. Mêmes propriétés de connexion
 * que le poller de révocation #306 (même origine : auth-service), intervalle séparé
 * ({@code epos.matieres-retirees.refresh-ms}) pour pouvoir les découpler un jour.
 */
@Configuration
public class RetiredMatiereConfig {

    @Bean
    public RetiredMatiereList retiredMatiereList() {
        return new RetiredMatiereList();
    }

    /** Posture de panne : voir {@link RetiredMatiereSyncClient}. */
    @Bean(initMethod = "start", destroyMethod = "close")
    public RetiredMatiereSyncClient retiredMatiereSyncClient(
            RetiredMatiereList retiredMatiereList,
            @Value("${epos.auth.base-url:http://auth-service:8081}") String authBaseUrl,
            @Value("${jwt.secret}") String jwtSecret,
            @Value("${epos.matieres-retirees.refresh-ms:30000}") long refreshMs) {
        return new RetiredMatiereSyncClient(
                authBaseUrl, jwtSecret, retiredMatiereList, Duration.ofMillis(refreshMs));
    }
}
