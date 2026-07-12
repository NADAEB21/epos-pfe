package tn.epos.api_gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
 *
 * <p>cors.allowed-origins accepts a comma-separated list of origins so that
 * multiple dev clients (Angular on :4200, Flutter Web on :4300, …) can be
 * authorised without code changes — just update the env variable and restart.
 */
@Configuration
public class GatewayCorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter(
            @Value("${cors.allowed-origins:http://localhost:4200}") String allowedOriginsRaw) {

        List<String> allowedOrigins = Arrays.stream(allowedOriginsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());

        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(allowedOrigins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return new CorsWebFilter(source);
    }
}