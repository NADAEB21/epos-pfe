package tn.epos.scoring_service.config;


import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import tn.epos.common.security.HmacJwtDecoders;
import tn.epos.common.security.ScopedAuthoritiesConverter;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // active @PreAuthorize sur les controllers
public class SecurityConfig {

    static final int MIN_SECRET_BYTES = 32;

    @Value("${jwt.secret}")
    private String jwtSecret;

    // CORS is intentionally NOT configured here. api-gateway owns CORS via its
    // CorsWebFilter and is the only host-reachable entry point; configuring CORS
    // again at the service layer doubled the Access-Control-Allow-Origin header
    // on real (non-preflight) responses and broke browser logins while the
    // gateway-only preflight smoke stayed green.

    @PostConstruct
    void validateJwtSecret() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET environment variable is required but not set. " +
                            "Set JWT_SECRET to a random value of at least 32 bytes (256 bits) for HS256.");
        }
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET is too short. HS256 requires at least 32 bytes (256 bits). " +
                            "Regenerate a longer secret and set JWT_SECRET.");
        }
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http
                // CSRF désactivé : API stateless JWT, pas de session cookie
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                );
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        // Algorithm auto-selection by secret byte length — see HmacJwtDecoders
        // and reference-jwt-algorithm-by-secret-length. Hard-coding HS256 used
        // to reject any secret ≥48 bytes with "Another algorithm expected".
        return HmacJwtDecoders.autoSelectByLength(jwtSecret);
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        // ScopedAuthoritiesConverter expands "ROLE_RESPONSABLE_MATIERE:<id>" into
        // a bare "ROLE_RESPONSABLE_MATIERE" (so hasRole matches) plus the scoped
        // form (so per-matiere checks stay possible). See issue #46.
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new ScopedAuthoritiesConverter());
        return converter;
    }
}
