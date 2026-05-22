package tn.epos.scoring_service.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Maps the JWT {@code authorities} claim to Spring Security authorities,
 * expanding subject-scoped authorities into a bare role plus the scoped form.
 *
 * <p>auth-service emits a RESPONSABLE_MATIERE role as the scoped authority
 * {@code ROLE_RESPONSABLE_MATIERE:<matiere_id>} (see auth-service
 * {@code JwtService.generateAccessToken}). Spring's {@code hasRole('X')} matches
 * the authority {@code ROLE_X} <em>exactly</em>, so a scoped authority never
 * satisfies {@code hasRole} — every RESPONSABLE_MATIERE user was locked out of
 * scoring-service with a 403 (issue #46).
 *
 * <p>For each claim entry containing {@code ':'}, this converter grants BOTH:
 * <ul>
 *   <li>the bare role prefix — {@code ROLE_RESPONSABLE_MATIERE} — which
 *       satisfies {@code hasRole('RESPONSABLE_MATIERE')};</li>
 *   <li>the original scoped form — {@code ROLE_RESPONSABLE_MATIERE:5} — kept so
 *       finer-grained per-matiere checks remain possible.</li>
 * </ul>
 * Global authorities (no {@code ':'}) pass through unchanged.
 *
 * <p>Twin of {@code tn.epos.exam_service.config.ScopedAuthoritiesConverter}
 * (issue #58). The two copies should be folded into the shared {@code epos-common}
 * module when it lands — see issue #68.
 */
public final class ScopedAuthoritiesConverter
        implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final String AUTHORITIES_CLAIM = "authorities";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        return expand(jwt.getClaimAsStringList(AUTHORITIES_CLAIM));
    }

    /**
     * Expands raw authority strings into granted authorities. Package-private so
     * it is unit-testable without minting a JWT.
     */
    static Collection<GrantedAuthority> expand(List<String> authorities) {
        if (authorities == null) {
            return List.of();
        }
        // LinkedHashSet: stable order + de-duplicates the bare role when a user
        // holds several scopes of the same role (e.g. ":5" and ":7").
        Set<GrantedAuthority> expanded = new LinkedHashSet<>();
        for (String authority : authorities) {
            if (authority == null || authority.isBlank()) {
                continue;
            }
            String trimmed = authority.trim();
            int colon = trimmed.indexOf(':');
            if (colon > 0) {
                expanded.add(new SimpleGrantedAuthority(trimmed.substring(0, colon)));
            }
            expanded.add(new SimpleGrantedAuthority(trimmed));
        }
        return expanded;
    }
}
