package tn.epos.common.security.revocation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #306 — le décorateur : signature valide + porteur non révoqué, sinon {@link JwtException}
 * (le même canal qu'un jeton expiré, donc 401 côté resource server).
 */
class RevocationAwareJwtDecoderTest {

    private static final Instant STAMP = Instant.parse("2026-08-13T10:00:00Z");

    private final JwtDecoder delegate = mock(JwtDecoder.class);
    private final TokenRevocationList list = new TokenRevocationList();
    private final RevocationAwareJwtDecoder decoder = new RevocationAwareJwtDecoder(delegate, list);

    @BeforeEach
    void setUp() {
        list.replaceAll(Map.of(60L, STAMP));
    }

    private Jwt jwt(Object userIdClaim, Instant issuedAt) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "HS256")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt == null ? Instant.now().plusSeconds(60) : issuedAt.plusSeconds(86400));
        if (userIdClaim != null) {
            builder.claim("userId", userIdClaim);
        } else {
            builder.claim("sub", "x"); // au moins un claim, sinon Jwt.build() refuse
        }
        return builder.build();
    }

    @Test
    @DisplayName("porteur révoqué + jeton antérieur → JwtException (le 401 du resource server)")
    void revoqueRejete() {
        when(delegate.decode(anyString())).thenReturn(jwt(60L, STAMP.minusSeconds(1)));
        assertThatThrownBy(() -> decoder.decode("t"))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("révoqué");
    }

    @Test
    @DisplayName("jeton émis APRÈS la révocation → passe (le re-login doit fonctionner)")
    void posterieurPasse() {
        Jwt jwt = jwt(60L, STAMP.plusSeconds(1));
        when(delegate.decode(anyString())).thenReturn(jwt);
        assertThat(decoder.decode("t")).isSameAs(jwt);
    }

    @Test
    @DisplayName("claim userId en Integer (parseur JSON) → comparé quand même — le piège du type concret")
    void userIdIntegerCompare() {
        when(delegate.decode(anyString())).thenReturn(jwt(60, STAMP.minusSeconds(1)));
        assertThatThrownBy(() -> decoder.decode("t")).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("porteur non listé → passe sans autre contrôle")
    void nonListePasse() {
        Jwt jwt = jwt(999L, STAMP.minusSeconds(3600));
        when(delegate.decode(anyString())).thenReturn(jwt);
        assertThat(decoder.decode("t")).isSameAs(jwt);
    }

    @Test
    @DisplayName("l'échec du délégué (signature, expiration) remonte inchangé")
    void echecDeleguePropage() {
        when(delegate.decode(anyString())).thenThrow(new JwtException("expired"));
        assertThatThrownBy(() -> decoder.decode("t"))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("expired");
    }
}
