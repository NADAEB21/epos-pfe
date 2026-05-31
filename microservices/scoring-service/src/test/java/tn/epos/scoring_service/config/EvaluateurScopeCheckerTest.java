package tn.epos.scoring_service.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("EvaluateurScopeChecker — unit tests (#85, #91, ADR 0007)")
class EvaluateurScopeCheckerTest {

    private final EvaluateurScopeChecker checker = new EvaluateurScopeChecker();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** JWT-backed auth (principal is a Jwt with a userId claim), like the real resource server. */
    private void authAsJwt(Object userIdClaim, String... authorities) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("u@epos.tn");
        if (userIdClaim != null) {
            builder.claim("userId", userIdClaim);
        } else {
            // a claim is required for Jwt to build; use one that is not userId
            builder.claim("scope", "read");
        }
        var grants = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(builder.build(), grants));
    }

    /** Non-JWT auth (principal is a String), to prove userId resolution degrades to null. */
    private void authNonJwt(String... authorities) {
        var grants = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u", "p", grants));
    }

    // ── isUnrestricted ──────────────────────────────────────────────────────

    @Test
    @DisplayName("SUPER_ADMIN is unrestricted")
    void unrestricted_superAdmin() {
        authNonJwt("ROLE_SUPER_ADMIN");
        assertThat(checker.isUnrestricted()).isTrue();
    }

    @Test
    @DisplayName("RESPONSABLE_MATIERE is unrestricted (oversight + corrections)")
    void unrestricted_responsable() {
        authNonJwt("ROLE_RESPONSABLE_MATIERE", "ROLE_RESPONSABLE_MATIERE:5");
        assertThat(checker.isUnrestricted()).isTrue();
    }

    @Test
    @DisplayName("EVALUATEUR is constrained")
    void constrained_evaluateur() {
        authAsJwt(42L, "ROLE_EVALUATEUR");
        assertThat(checker.isUnrestricted()).isFalse();
    }

    @Test
    @DisplayName("no authentication is constrained")
    void constrained_noAuth() {
        SecurityContextHolder.clearContext();
        assertThat(checker.isUnrestricted()).isFalse();
    }

    // ── getCallerUserId ─────────────────────────────────────────────────────

    @Test
    @DisplayName("reads userId claim as Long")
    void callerId_long() {
        authAsJwt(42L, "ROLE_EVALUATEUR");
        assertThat(checker.getCallerUserId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("reads userId claim when serialized as Integer")
    void callerId_integer() {
        authAsJwt(42, "ROLE_EVALUATEUR");
        assertThat(checker.getCallerUserId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("reads userId claim when serialized as String")
    void callerId_string() {
        authAsJwt("42", "ROLE_EVALUATEUR");
        assertThat(checker.getCallerUserId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("null when userId claim absent")
    void callerId_absent() {
        authAsJwt(null, "ROLE_EVALUATEUR");
        assertThat(checker.getCallerUserId()).isNull();
    }

    @Test
    @DisplayName("null when principal is not a Jwt")
    void callerId_nonJwt() {
        authNonJwt("ROLE_EVALUATEUR");
        assertThat(checker.getCallerUserId()).isNull();
    }

    @Test
    @DisplayName("null when no authentication")
    void callerId_noAuth() {
        SecurityContextHolder.clearContext();
        assertThat(checker.getCallerUserId()).isNull();
    }

    // ── isCaller ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("unrestricted caller may act on any owner (even a mismatched id)")
    void isCaller_unrestricted_any() {
        authNonJwt("ROLE_SUPER_ADMIN");
        assertThat(checker.isCaller(999L)).isTrue();
        assertThat(checker.isCaller(null)).isTrue();
    }

    @Test
    @DisplayName("constrained évaluateur may act on their own rotations")
    void isCaller_constrained_own() {
        authAsJwt(42L, "ROLE_EVALUATEUR");
        assertThat(checker.isCaller(42L)).isTrue();
    }

    @Test
    @DisplayName("constrained évaluateur may not act on a foreign rotation")
    void isCaller_constrained_foreign() {
        authAsJwt(42L, "ROLE_EVALUATEUR");
        assertThat(checker.isCaller(99L)).isFalse();
    }

    @Test
    @DisplayName("constrained évaluateur may not act on a null owner (broken chain)")
    void isCaller_constrained_nullOwner() {
        authAsJwt(42L, "ROLE_EVALUATEUR");
        assertThat(checker.isCaller(null)).isFalse();
    }

    @Test
    @DisplayName("constrained caller with no resolvable userId is denied")
    void isCaller_constrained_nullCaller() {
        authAsJwt(null, "ROLE_EVALUATEUR");
        assertThat(checker.isCaller(42L)).isFalse();
    }

    // ── checkOwnership ──────────────────────────────────────────────────────

    @Test
    @DisplayName("checkOwnership throws 403 when denied")
    void checkOwnership_throws() {
        authAsJwt(42L, "ROLE_EVALUATEUR");
        assertThatThrownBy(() -> checker.checkOwnership(99L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("checkOwnership returns silently when allowed")
    void checkOwnership_silent() {
        authAsJwt(42L, "ROLE_EVALUATEUR");
        assertThatCode(() -> checker.checkOwnership(42L)).doesNotThrowAnyException();
    }
}
