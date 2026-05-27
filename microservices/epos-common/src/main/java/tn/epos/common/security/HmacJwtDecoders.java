package tn.epos.common.security;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Builds a {@link JwtDecoder} whose HMAC algorithm matches the one auth-service's
 * issuer picks for the same secret.
 *
 * <p>auth-service signs via JJWT's {@code Keys.hmacShaKeyFor(secret)} which auto-
 * selects HS256 / HS384 / HS512 by UTF-8 byte length (≥32 / ≥48 / ≥64). Resource
 * servers that hard-code HS256 (the {@code NimbusJwtDecoder.withSecretKey} default)
 * reject every token whose signature was produced with a longer-key algorithm,
 * with {@code "Signed JWT rejected: Another algorithm expected, or no matching
 * key(s) found"} → 401.
 *
 * <p>This helper mirrors auth-service's selection logic so resource servers
 * accept whatever auth-service issues, regardless of operator secret length.
 * Extracted from exam-service / scoring-service SecurityConfig where it was
 * duplicated verbatim — same precedent as {@link ScopedAuthoritiesConverter}
 * extraction in #68.
 */
public final class HmacJwtDecoders {

    /** HS256 minimum per RFC 7518 §3.2 and Nimbus's MACVerifier. */
    private static final int HS256_MIN_BYTES = 32;
    private static final int HS384_MIN_BYTES = 48;
    private static final int HS512_MIN_BYTES = 64;

    private HmacJwtDecoders() {
        // utility class
    }

    /**
     * Returns a {@link JwtDecoder} that verifies tokens signed with the HMAC
     * algorithm corresponding to {@code secret}'s UTF-8 byte length.
     *
     * @throws IllegalArgumentException if the secret is null/blank or shorter
     *                                  than 32 bytes (the HS256 floor).
     */
    public static JwtDecoder autoSelectByLength(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT secret must not be null or blank");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < HS256_MIN_BYTES) {
            throw new IllegalArgumentException(
                    "JWT secret is too short: " + keyBytes.length +
                    " bytes (minimum " + HS256_MIN_BYTES + " for HS256)");
        }

        MacAlgorithm alg;
        String javaName;
        if (keyBytes.length >= HS512_MIN_BYTES) {
            alg = MacAlgorithm.HS512;
            javaName = "HmacSHA512";
        } else if (keyBytes.length >= HS384_MIN_BYTES) {
            alg = MacAlgorithm.HS384;
            javaName = "HmacSHA384";
        } else {
            alg = MacAlgorithm.HS256;
            javaName = "HmacSHA256";
        }
        SecretKeySpec key = new SecretKeySpec(keyBytes, javaName);
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(alg).build();
    }
}
