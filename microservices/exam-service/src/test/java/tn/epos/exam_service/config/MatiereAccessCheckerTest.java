package tn.epos.exam_service.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MatiereAccessChecker — unit tests (#81)")
class MatiereAccessCheckerTest {

    private final MatiereAccessChecker checker = new MatiereAccessChecker();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authWith(String... authorities) {
        var grants = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u", "p", grants));
    }

    @Test
    @DisplayName("SUPER_ADMIN bypasses the check for any matiere_id")
    void superAdmin_accessAll() {
        authWith("ROLE_SUPER_ADMIN");
        assertThat(checker.canAccess(5L)).isTrue();
        assertThat(checker.canAccess(999L)).isTrue();
    }

    @Test
    @DisplayName("RESPONSABLE_MATIERE:5 can access matiere_id=5")
    void responsable_accessOwnScope() {
        authWith("ROLE_RESPONSABLE_MATIERE", "ROLE_RESPONSABLE_MATIERE:5");
        assertThat(checker.canAccess(5L)).isTrue();
    }

    @Test
    @DisplayName("RESPONSABLE_MATIERE:5 cannot access matiere_id=7")
    void responsable_deniedOtherScope() {
        authWith("ROLE_RESPONSABLE_MATIERE", "ROLE_RESPONSABLE_MATIERE:5");
        assertThat(checker.canAccess(7L)).isFalse();
    }

    @Test
    @DisplayName("RESPONSABLE_MATIERE with multiple scopes can access either")
    void responsable_multipleScopes() {
        authWith("ROLE_RESPONSABLE_MATIERE",
                 "ROLE_RESPONSABLE_MATIERE:5",
                 "ROLE_RESPONSABLE_MATIERE:7");
        assertThat(checker.canAccess(5L)).isTrue();
        assertThat(checker.canAccess(7L)).isTrue();
        assertThat(checker.canAccess(99L)).isFalse();
    }

    @Test
    @DisplayName("EVALUATEUR is denied for any matiere_id")
    void evaluateur_denied() {
        authWith("ROLE_EVALUATEUR");
        assertThat(checker.canAccess(5L)).isFalse();
    }

    @Test
    @DisplayName("null Authentication → denied")
    void nullAuth_denied() {
        SecurityContextHolder.clearContext();
        assertThat(checker.canAccess(5L)).isFalse();
    }

    @Test
    @DisplayName("null matiereId → denied")
    void nullMatiereId_denied() {
        authWith("ROLE_SUPER_ADMIN");
        assertThat(checker.canAccess(null)).isFalse();
    }

    @Test
    @DisplayName("checkAccess() throws AccessDeniedException when denied")
    void checkAccess_throwsOnDeny() {
        authWith("ROLE_RESPONSABLE_MATIERE", "ROLE_RESPONSABLE_MATIERE:5");
        assertThatThrownBy(() -> checker.checkAccess(7L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("matiere_id=7");
    }

    @Test
    @DisplayName("checkAccess() returns silently when allowed")
    void checkAccess_silentOnAllow() {
        authWith("ROLE_SUPER_ADMIN");
        checker.checkAccess(5L); // no exception
    }

    // ───────────────────────────────────────────────────────────────────────
    // #95 — list-endpoint scope helpers
    // ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isUnrestricted() true for SUPER_ADMIN")
    void unrestricted_superAdmin() {
        authWith("ROLE_SUPER_ADMIN");
        assertThat(checker.isUnrestricted()).isTrue();
    }

    @Test
    @DisplayName("isUnrestricted() false for RESPONSABLE_MATIERE")
    void unrestricted_responsable() {
        authWith("ROLE_RESPONSABLE_MATIERE", "ROLE_RESPONSABLE_MATIERE:5");
        assertThat(checker.isUnrestricted()).isFalse();
    }

    @Test
    @DisplayName("isUnrestricted() false when no auth")
    void unrestricted_noAuth() {
        SecurityContextHolder.clearContext();
        assertThat(checker.isUnrestricted()).isFalse();
    }

    @Test
    @DisplayName("getAccessibleMatiereIds() returns scoped ids")
    void scope_responsable() {
        authWith("ROLE_RESPONSABLE_MATIERE",
                 "ROLE_RESPONSABLE_MATIERE:5",
                 "ROLE_RESPONSABLE_MATIERE:7");
        assertThat(checker.getAccessibleMatiereIds()).containsExactlyInAnyOrder(5L, 7L);
    }

    @Test
    @DisplayName("getAccessibleMatiereIds() empty for SUPER_ADMIN (use isUnrestricted instead)")
    void scope_superAdmin_empty() {
        authWith("ROLE_SUPER_ADMIN");
        assertThat(checker.getAccessibleMatiereIds()).isEmpty();
    }

    @Test
    @DisplayName("getAccessibleMatiereIds() empty for EVALUATEUR")
    void scope_evaluateur_empty() {
        authWith("ROLE_EVALUATEUR");
        assertThat(checker.getAccessibleMatiereIds()).isEmpty();
    }

    @Test
    @DisplayName("getAccessibleMatiereIds() empty when no auth")
    void scope_noAuth_empty() {
        SecurityContextHolder.clearContext();
        assertThat(checker.getAccessibleMatiereIds()).isEmpty();
    }

    @Test
    @DisplayName("getAccessibleMatiereIds() ignores malformed authority")
    void scope_malformed_skipped() {
        authWith("ROLE_RESPONSABLE_MATIERE:abc", "ROLE_RESPONSABLE_MATIERE:5");
        assertThat(checker.getAccessibleMatiereIds()).containsExactly(5L);
    }
}
