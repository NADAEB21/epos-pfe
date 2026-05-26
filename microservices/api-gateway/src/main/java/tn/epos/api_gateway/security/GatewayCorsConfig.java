package tn.epos.api_gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Single source of CORS truth for the gateway.
 *
 * Configured as a CorsWebFilter (not via spring.cloud.gateway.globalcors yaml)
 * because the yaml path double-processes CORS on routed non-preflight requests
 * — RoutePredicateHandlerMapping's built-in CORS runs once, then the per-route
 * CorsConfiguration that CorsGatewayFilterApplicationListener installs runs
 * again and rejects with "Invalid CORS request" on the already-being-committed
 * response. A WebFilter runs once, before any HandlerMapping, and short-circuits
 * preflight by returning Mono.empty() after writing the response.
 */
@Configuration
public class GatewayCorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter(
            @Value("${cors.allowed-origin:http://localhost:4200}") String allowedOrigin) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of(allowedOrigin));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return new CorsWebFilter(source);
    }
}
