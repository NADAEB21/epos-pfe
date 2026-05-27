package tn.epos.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScopedAuthoritiesConverter}. Moved from exam-service +
 * scoring-service into {@code epos-common} as part of #68. Previously
 * duplicated tests with identical assertions (#58 / #46).
 */
@DisplayName("ScopedAuthoritiesConverter - expansion of scoped JWT authorities")
class ScopedAuthoritiesConverterTest {

    @Test
    @DisplayName("Scoped authority is expanded into bare role + scoped form")
    void scopedAuthority_isExpanded() {
        Collection<GrantedAuthority> result =
                ScopedAuthoritiesConverter.expand(List.of("ROLE_RESPONSABLE_MATIERE:5"));

        assertThat(result)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_RESPONSABLE_MATIERE", "ROLE_RESPONSABLE_MATIERE:5");
    }

    @Test
    @DisplayName("Global authorities (no colon) pass through unchanged")
    void globalAuthorities_passThrough() {
        Collection<GrantedAuthority> result =
                ScopedAuthoritiesConverter.expand(List.of("ROLE_SUPER_ADMIN", "ROLE_EVALUATEUR"));

        assertThat(result)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_SUPER_ADMIN", "ROLE_EVALUATEUR");
    }

    @Test
    @DisplayName("Multiple scoped authorities of the same role: bare role is de-duplicated")
    void multipleScopes_bareRoleDeduped() {
        Collection<GrantedAuthority> result = ScopedAuthoritiesConverter.expand(
                List.of("ROLE_RESPONSABLE_MATIERE:5", "ROLE_RESPONSABLE_MATIERE:7"));

        assertThat(result)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_RESPONSABLE_MATIERE",
                        "ROLE_RESPONSABLE_MATIERE:5",
                        "ROLE_RESPONSABLE_MATIERE:7");
    }

    @Test
    @DisplayName("Null claim list returns empty collection")
    void nullClaim_returnsEmpty() {
        assertThat(ScopedAuthoritiesConverter.expand(null)).isEmpty();
    }

    @Test
    @DisplayName("Blank entries are skipped")
    void blankEntries_areSkipped() {
        Collection<GrantedAuthority> result = ScopedAuthoritiesConverter.expand(
                List.of("", "   ", "ROLE_SUPER_ADMIN"));

        assertThat(result)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_SUPER_ADMIN");
    }

    @Test
    @DisplayName("Leading colon authority is kept literal (no bare-role emission)")
    void leadingColon_keptLiteral() {
        Collection<GrantedAuthority> result =
                ScopedAuthoritiesConverter.expand(List.of(":5"));

        assertThat(result)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly(":5");
    }
}
