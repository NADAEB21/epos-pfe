package tn.epos.auth_service.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tn.epos.auth_service.entity.RoleType;
import tn.epos.auth_service.entity.User;
import tn.epos.auth_service.entity.UserRole;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class JwtService {

    static final int MIN_SECRET_BYTES = 32;

    private final SecretKey signingKey;
    private final long accessTokenExpiryMs;
    private final long refreshTokenExpiryMs;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiry-ms:86400000}") long accessTokenExpiryMs,
            @Value("${jwt.refresh-token-expiry-ms:604800000}") long refreshTokenExpiryMs) {
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
        this.accessTokenExpiryMs = accessTokenExpiryMs;
        this.refreshTokenExpiryMs = refreshTokenExpiryMs;
    }

    // -------------------------------------------------------------------------
    // Access token
    // -------------------------------------------------------------------------

    /**
     * Generates a signed JWT access token.
     * Claims: sub=email, userId, authorities (see CLAUDE.md for format).
     */
    public String generateAccessToken(User user, List<UserRole> roles) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpiryMs);

        return Jwts.builder()
                .subject(user.getEmail())
                .claim("userId", user.getId())
                .claim("authorities", buildAuthorities(roles))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    // -------------------------------------------------------------------------
    // Refresh token (opaque)
    // -------------------------------------------------------------------------

    /**
     * Generates a cryptographically random opaque refresh token value.
     * The caller must hash this before persisting (see {@link #hashToken}).
     *
     * @return raw token string (URL-safe Base64, 256 bits of entropy)
     */
    public String generateRefreshTokenValue() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Generates a new family ID for a refresh token rotation chain.
     */
    public String generateFamilyId() {
        return UUID.randomUUID().toString();
    }

    // -------------------------------------------------------------------------
    // Token validation & claim extraction
    // -------------------------------------------------------------------------

    /**
     * Returns true if the token has a valid signature and has not expired.
     */
    public boolean isTokenValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    public Long extractUserId(String token) {
        return parseClaims(token).get("userId", Long.class);
    }

    @SuppressWarnings("unchecked")
    public List<String> extractAuthorities(String token) {
        return (List<String>) parseClaims(token).get("authorities");
    }

    /** #306 — l'instant d'émission ({@code iat}), comparé à l'estampille de révocation. */
    public java.time.Instant extractIssuedAt(String token) {
        Date issuedAt = parseClaims(token).getIssuedAt();
        return issuedAt == null ? null : issuedAt.toInstant();
    }

    // -------------------------------------------------------------------------
    // Authority string builder
    // -------------------------------------------------------------------------

    /**
     * Converts a list of UserRole entities into JWT authority strings.
     *
     * Format:
     *   ROLE_RESPONSABLE_MATIERE:5   (scoped — matiereId non-null)
     *   ROLE_SUPER_ADMIN             (global — matiereId null)
     *   ROLE_EVALUATEUR              (global — matiereId always null)
     */
    public List<String> buildAuthorities(List<UserRole> roles) {
        return roles.stream()
                .map(this::toAuthorityString)
                .toList();
    }

    private String toAuthorityString(UserRole userRole) {
        String base = "ROLE_" + userRole.getRole().name();
        if (userRole.getRole() == RoleType.RESPONSABLE_MATIERE && userRole.getMatiereId() != null) {
            return base + ":" + userRole.getMatiereId();
        }
        return base;
    }

    // -------------------------------------------------------------------------
    // Token hashing (SHA-256) — used for both refresh tokens and reset tokens
    // -------------------------------------------------------------------------

    /**
     * Returns the hex-encoded SHA-256 hash of the raw token value.
     * This is what gets stored in the database — never the raw value.
     */
    public String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JVM spec — this cannot happen
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
