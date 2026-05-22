package tn.epos.exam_service.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScopedAuthoritiesConverter} — issue #58.
 * Exercises {@code expand} directly, so no JWT needs to be minted.
 */
@DisplayName("ScopedAuthoritiesConverter - expansion of scoped JWT authorities")
class ScopedAuthoritiesConverterTest {

    private static List<String> names(Collection<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    @DisplayName("scoped authority expands to bare role + scoped form")
    void scopedAuthority_expandsToBareRoleAndScopedForm() {
        Collection<GrantedAuthority> result =
                ScopedAuthoritiesConverter.expand(List.of("ROLE_RESPONSABLE_MATIERE:5"));

        // bare role is what hasRole('RESPONSABLE_MATIERE') matches — the #58 fix
        assertThat(names(result))
                .containsExactly("ROLE_RESPONSABLE_MATIERE", "ROLE_RESPONSABLE_MATIERE:5");
    }

    @Test
    @DisplayName("global authority passes through unchanged")
    void globalAuthority_passesThroughUnchanged() {
        Collection<GrantedAuthority> result =
                ScopedAuthoritiesConverter.expand(List.of("ROLE_SUPER_ADMIN", "ROLE_EVALUATEUR"));

        assertThat(names(result)).containsExactly("ROLE_SUPER_ADMIN", "ROLE_EVALUATEUR");
    }

    @Test
    @DisplayName("multiple scopes of the same role yield a single, de-duplicated bare role")
    void multipleScopesOnSameRole_yieldOneBareRoleNoDuplicates() {
        Collection<GrantedAuthority> result = ScopedAuthoritiesConverter.expand(
                List.of("ROLE_RESPONSABLE_MATIERE:5", "ROLE_RESPONSABLE_MATIERE:7"));

        assertThat(names(result)).containsExactly(
                "ROLE_RESPONSABLE_MATIERE",
                "ROLE_RESPONSABLE_MATIERE:5",
                "ROLE_RESPONSABLE_MATIERE:7");
    }

    @Test
    @DisplayName("null authorities claim yields no authorities")
    void nullClaim_yieldsEmptyAuthorities() {
        assertThat(ScopedAuthoritiesConverter.expand(null)).isEmpty();
    }

    @Test
    @DisplayName("null and blank entries are skipped")
    void blankAndNullEntries_areSkipped() {
        Collection<GrantedAuthority> result = ScopedAuthoritiesConverter.expand(
                Arrays.asList("ROLE_SUPER_ADMIN", "   ", null));

        assertThat(names(result)).containsExactly("ROLE_SUPER_ADMIN");
    }

    @Test
    @DisplayName("malformed authority starting with ':' does not emit an empty bare role")
    void leadingColon_doesNotEmitEmptyRole() {
        Collection<GrantedAuthority> result =
                ScopedAuthoritiesConverter.expand(List.of(":5"));

        assertThat(names(result)).containsExactly(":5");
    }
}
