package tn.epos.exam_service.config;

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
import tn.epos.common.security.revocation.RevocationAwareJwtDecoder;
import tn.epos.common.security.revocation.RevocationSyncClient;
import tn.epos.common.security.revocation.TokenRevocationList;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // ← active @PreAuthorize
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
                // CSRF disabled: stateless JWT API, tokens carried in Authorization header
                // (no session cookie -> no CSRF attack surface). Same model as auth-service.
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
    public JwtDecoder jwtDecoder(TokenRevocationList revocationList) {
        // auth-service signs with the HMAC algorithm JJWT picks for the secret's
        // length (HS256/384/512 at ≥32/48/64 bytes). HmacJwtDecoders mirrors that
        // selection so the resource server accepts whatever auth-service issues —
        // hard-coding HS256 here used to reject any secret ≥48 bytes ("Another
        // algorithm expected", see reference-jwt-algorithm-by-secret-length).
        //
        // #306 — enveloppé : le jeton doit être postérieur à la dernière révocation
        // de son porteur (même mécanisme que scoring, liste rapatriée d'auth-service).
        return new RevocationAwareJwtDecoder(
                HmacJwtDecoders.autoSelectByLength(jwtSecret), revocationList);
    }

    @Bean
    public TokenRevocationList tokenRevocationList() {
        return new TokenRevocationList();
    }

    /** #306 — voir la posture de panne dans {@link RevocationSyncClient}. */
    @Bean(initMethod = "start", destroyMethod = "close")
    public RevocationSyncClient revocationSyncClient(
            TokenRevocationList revocationList,
            @Value("${epos.auth.base-url:http://auth-service:8081}") String authBaseUrl,
            @Value("${epos.revocation.refresh-ms:30000}") long refreshMs) {
        return new RevocationSyncClient(
                authBaseUrl, jwtSecret, revocationList, Duration.ofMillis(refreshMs));
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        // ScopedAuthoritiesConverter expands "ROLE_RESPONSABLE_MATIERE:<id>" into
        // a bare "ROLE_RESPONSABLE_MATIERE" (so hasRole matches) plus the scoped
        // form (so per-matiere checks stay possible). See issue #58.
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new ScopedAuthoritiesConverter());
        return converter;
    }
}
