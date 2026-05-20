package tn.epos.api_gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Verifies JWT access tokens issued by auth-service. The gateway only ever
 * VERIFIES — it never issues tokens — but it must use the identical HS256
 * secret, so the fail-fast secret checks mirror auth-service's JwtService.
 */
@Component
public class GatewayJwtService {

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey signingKey;

    public GatewayJwtService(@Value("${jwt.secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET environment variable is required but not set. " +
                    "Set JWT_SECRET to a random value of at least 32 bytes (256 bits) for HS256.");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET is too short. HS256 requires at least 32 bytes (256 bits). " +
                    "Regenerate a longer secret and set JWT_SECRET.");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parses and verifies a token, returning its claims.
     *
     * @throws io.jsonwebtoken.JwtException     if the signature is invalid or the token expired
     * @throws IllegalArgumentException         if the token is null/blank/malformed
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
