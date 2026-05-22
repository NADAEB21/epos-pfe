package tn.epos.api_gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayJwtServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-1234";

    private final GatewayJwtService service = new GatewayJwtService(SECRET);

    private String token(SecretKey key, Date expiry) {
        return Jwts.builder()
                .subject("user@epos.tn")
                .claim("userId", 7L)
                .claim("authorities", List.of("ROLE_EVALUATEUR"))
                .issuedAt(new Date())
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    private SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesValidToken() {
        Claims claims = service.parse(token(key(SECRET), new Date(System.currentTimeMillis() + 60_000)));

        assertThat(claims.getSubject()).isEqualTo("user@epos.tn");
        assertThat(claims.get("userId", Long.class)).isEqualTo(7L);
        assertThat(claims.get("authorities", List.class)).containsExactly("ROLE_EVALUATEUR");
    }

    @Test
    void rejectsExpiredToken() {
        String expired = token(key(SECRET), new Date(System.currentTimeMillis() - 1_000));

        assertThatThrownBy(() -> service.parse(expired)).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        String foreign = token(key("wrong-secret-wrong-secret-wrong-secret-99"),
                new Date(System.currentTimeMillis() + 60_000));

        assertThatThrownBy(() -> service.parse(foreign)).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsShortSecretAtConstruction() {
        assertThatThrownBy(() -> new GatewayJwtService("too-short"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBlankSecretAtConstruction() {
        assertThatThrownBy(() -> new GatewayJwtService("  "))
                .isInstanceOf(IllegalStateException.class);
    }
}
