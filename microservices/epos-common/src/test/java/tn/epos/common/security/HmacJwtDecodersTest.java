package tn.epos.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@link HmacJwtDecoders#autoSelectByLength(String)} produces a decoder
 * whose algorithm matches the one auth-service's JJWT issuer picks for the same
 * secret length — the cross-service contract that broke before the 2026-05-26
 * fix (see {@code reference-jwt-algorithm-by-secret-length}).
 */
class HmacJwtDecodersTest {

    private static final String SECRET_32 = "12345678901234567890123456789012";          // 32 bytes → HS256
    private static final String SECRET_48 = "123456789012345678901234567890123456789012345678";  // 48 bytes → HS384
    private static final String SECRET_64 = "1234567890123456789012345678901234567890123456789012345678901234"; // 64 bytes → HS512

    @ParameterizedTest(name = "secret of {0} bytes → {1} accepts its own token")
    @CsvSource({
            "32, HS256",
            "48, HS384",
            "64, HS512"
    })
    @DisplayName("decoder accepts a token signed at the alg the secret length implies")
    void decoderAcceptsTokenAtSelectedAlgorithm(int secretBytes, String algName) throws JOSEException {
        String secret = secretOfLength(secretBytes);
        JWSAlgorithm jwsAlg = JWSAlgorithm.parse(algName);
        String token = mintToken(secret, jwsAlg);

        JwtDecoder decoder = HmacJwtDecoders.autoSelectByLength(secret);
        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo("user@example.test");
        // Cast because Jwt#getClaim is generic <T> T — leaving T unresolved
        // makes assertThat ambiguous between its Predicate / IntPredicate overloads.
        assertThat((Object) jwt.getClaim("userId")).isEqualTo(42L);
        assertThat(jwt.getClaimAsStringList("authorities"))
                .containsExactly("ROLE_RESPONSABLE_MATIERE:1");
    }

    @Test
    @DisplayName("48-byte secret produces a decoder that REJECTS an HS256-signed token")
    void rejectsCrossAlgorithmToken() throws JOSEException {
        // A 48-byte secret picks HS384 in both issuer and decoder. If someone
        // tries to use the same secret to sign with HS256 (e.g. wrong issuer
        // config), the decoder must NOT accept it — that mismatch is exactly
        // the bug that prompted this fix.
        String secret = secretOfLength(48);
        String hs256Token = mintToken(secret, JWSAlgorithm.HS256);

        JwtDecoder decoder = HmacJwtDecoders.autoSelectByLength(secret);

        assertThatThrownBy(() -> decoder.decode(hs256Token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("null / blank / too-short secrets fail fast with IllegalArgumentException")
    void rejectsInvalidSecrets() {
        assertThatThrownBy(() -> HmacJwtDecoders.autoSelectByLength(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HmacJwtDecoders.autoSelectByLength("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HmacJwtDecoders.autoSelectByLength("short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too short");
    }

    private static String secretOfLength(int bytes) {
        return switch (bytes) {
            case 32 -> SECRET_32;
            case 48 -> SECRET_48;
            case 64 -> SECRET_64;
            default -> throw new IllegalArgumentException("unsupported test length: " + bytes);
        };
    }

    private static String mintToken(String secret, JWSAlgorithm alg) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user@example.test")
                .claim("userId", 42L)
                .claim("authorities", List.of("ROLE_RESPONSABLE_MATIERE:1"))
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                .build();
        SignedJWT signed = new SignedJWT(new JWSHeader(alg), claims);
        signed.sign(new MACSigner(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        return signed.serialize();
    }
}
