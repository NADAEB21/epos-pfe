package tn.epos.api_gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end checks of the gateway JWT filter. Routes target lb://&lt;service&gt;
 * which has no instances under test, so a request that CLEARS the JWT filter
 * ends in 503 (no downstream) — never 401. That lets these tests assert the
 * filter's behaviour without standing up the backend services.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewaySecurityIntegrationTest {

    /** Must match jwt.secret in application-test.yml. */
    private static final String SECRET = "test-secret-test-secret-test-secret-1234";

    @Autowired
    private WebTestClient webClient;

    private String validToken() {
        return Jwts.builder()
                .subject("admin@epos.tn")
                .claim("userId", 1L)
                .claim("authorities", List.of("ROLE_SUPER_ADMIN"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void protectedRoute_withoutToken_returns401() {
        webClient.get().uri("/api/v1/users")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void protectedRoute_withMalformedToken_returns401() {
        webClient.get().uri("/api/v1/users")
                .header("Authorization", "Bearer not-a-real-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void protectedRoute_withValidToken_clearsJwtFilter() {
        // A valid token passes the filter; routing then fails on the absent
        // downstream (503). The contract verified here: it is NOT 401.
        webClient.get().uri("/api/v1/users")
                .header("Authorization", "Bearer " + validToken())
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }

    @Test
    void publicLoginRoute_isNotBlockedByJwtFilter() {
        // login is public — the JWT filter must not 401 it even with no token.
        webClient.post().uri("/api/v1/auth/login")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }
}
