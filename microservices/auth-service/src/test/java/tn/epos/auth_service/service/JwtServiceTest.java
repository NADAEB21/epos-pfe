package tn.epos.auth_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.auth_service.entity.RoleType;
import tn.epos.auth_service.entity.User;
import tn.epos.auth_service.entity.UserRole;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for JwtService — no Spring context, no mocks.
 * JwtService has no collaborators so it is instantiated directly with
 * a test secret and explicit expiry values.
 */
@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

    /**
     * ≥ 256-bit (32 bytes) secret required for HMAC-SHA256.
     * This value must never appear in production config.
     */
    private static final String TEST_SECRET =
            "epos-test-secret-key-must-be-at-least-256-bits-long-for-hs256!!";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        // 24-hour access token, 7-day refresh token (matching production defaults)
        jwtService = new JwtService(TEST_SECRET, 86_400_000L, 604_800_000L);
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private User user() {
        return User.builder()
                .id(42L).email("alice@epos.tn")
                .nom("Ben Ali").prenom("Alice")
                .isActive(true).failedLoginAttempts(0)
                .build();
    }

    // =========================================================================
    // generateAccessToken()
    // =========================================================================

    @Test
    void generateAccessToken_containsCorrectClaims() {
        User user = user();
        String token = jwtService.generateAccessToken(user, List.of());

        assertThat(jwtService.extractEmail(token)).isEqualTo("alice@epos.tn");
        assertThat(jwtService.extractUserId(token)).isEqualTo(42L);
        assertThat(jwtService.extractAuthorities(token)).isEmpty();
    }

    @Test
    void generateAccessToken_scopedAuthority_correctFormat() {
        // RESPONSABLE_MATIERE with matiereId=5 → "ROLE_RESPONSABLE_MATIERE:5"
        // @PrePersist does not fire outside JPA context, so build() is safe here.
        UserRole scoped = UserRole.builder()
                .role(RoleType.RESPONSABLE_MATIERE).matiereId(5L).build();

        String token = jwtService.generateAccessToken(user(), List.of(scoped));
        List<String> authorities = jwtService.extractAuthorities(token);

        assertThat(authorities).containsExactly("ROLE_RESPONSABLE_MATIERE:5");
    }

    @Test
    void generateAccessToken_globalAuthority_correctFormat() {
        // SUPER_ADMIN has null matiereId → "ROLE_SUPER_ADMIN" (no colon suffix)
        UserRole global = UserRole.builder()
                .role(RoleType.SUPER_ADMIN).matiereId(null).build();

        String token = jwtService.generateAccessToken(user(), List.of(global));
        List<String> authorities = jwtService.extractAuthorities(token);

        assertThat(authorities).containsExactly("ROLE_SUPER_ADMIN");
    }

    // =========================================================================
    // isTokenValid()
    // =========================================================================

    @Test
    void validateToken_validToken_returnsTrue() {
        String token = jwtService.generateAccessToken(user(), List.of());

        assertThat(jwtService.isTokenValid(token)).isTrue();
    }

    @Test
    void validateToken_expiredToken_returnsFalse() {
        // A JwtService configured with -1 000 ms produces tokens already past expiry
        JwtService expiredService = new JwtService(TEST_SECRET, -1_000L, 604_800_000L);
        String expiredToken = expiredService.generateAccessToken(user(), List.of());

        // isTokenValid must return false — not throw
        assertThat(jwtService.isTokenValid(expiredToken)).isFalse();
    }

    @Test
    void validateToken_tamperedToken_returnsFalse() {
        String validToken = jwtService.generateAccessToken(user(), List.of());

        // JWT structure: header.payload.signature
        // Replacing the last character of the signature invalidates the HMAC.
        String[] parts = validToken.split("\\.");
        char last = parts[2].charAt(parts[2].length() - 1);
        String corruptedSig = parts[2].substring(0, parts[2].length() - 1)
                + (last == 'A' ? 'B' : 'A');
        String tamperedToken = parts[0] + "." + parts[1] + "." + corruptedSig;

        assertThat(jwtService.isTokenValid(tamperedToken)).isFalse();
    }

    // =========================================================================
    // hashToken()
    // =========================================================================

    @Test
    void hashToken_sameInput_sameHash() {
        String hash1 = jwtService.hashToken("some-opaque-token-value");
        String hash2 = jwtService.hashToken("some-opaque-token-value");

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void hashToken_differentInput_differentHash() {
        String hashA = jwtService.hashToken("token-alpha");
        String hashB = jwtService.hashToken("token-beta");

        assertThat(hashA).isNotEqualTo(hashB);
    }

    // -------------------------------------------------------------------------
    // Fail-fast on missing secret (issue #6)
    // -------------------------------------------------------------------------

    @Test
    void constructor_nullSecret_throwsIllegalStateException() {
        assertThatThrownBy(() -> new JwtService(null, 86_400_000L, 604_800_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void constructor_blankSecret_throwsIllegalStateException() {
        assertThatThrownBy(() -> new JwtService("   ", 86_400_000L, 604_800_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void constructor_shortSecret_throwsIllegalStateExceptionBeforeJjwt() {
        // 31 bytes — one short of the HS256 minimum. Our explicit check must fire
        // first and surface a friendly operator message, instead of leaking
        // JJWT's generic WeakKeyException up the stack.
        String shortSecret = "a".repeat(31);

        assertThatThrownBy(() -> new JwtService(shortSecret, 86_400_000L, 604_800_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET is too short")
                .hasMessageContaining("32 bytes");
    }
}
