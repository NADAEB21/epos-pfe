package tn.epos.exam_service.config;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Enforces per-matiere authorization based on the caller's JWT authorities.
 *
 * <p>SUPER_ADMIN bypasses the check. RESPONSABLE_MATIERE must hold the scoped
 * authority {@code ROLE_RESPONSABLE_MATIERE:<matiereId>} matching the resource.
 *
 * <p>Used from service methods after loading the target entity — for endpoints
 * that take an opaque {@code {id}} path variable, controller-level SpEL cannot
 * see the resource's matiereId without loading it first.
 */
@Component
public class MatiereAccessChecker {

    private static final String SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final String RESP_PREFIX = "ROLE_RESPONSABLE_MATIERE:";

    public boolean canAccess(Long matiereId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || matiereId == null) {
            return false;
        }
        String scoped = RESP_PREFIX + matiereId;
        for (GrantedAuthority a : auth.getAuthorities()) {
            String authority = a.getAuthority();
            if (SUPER_ADMIN.equals(authority) || scoped.equals(authority)) {
                return true;
            }
        }
        return false;
    }

    public void checkAccess(Long matiereId) {
        if (!canAccess(matiereId)) {
            throw new AccessDeniedException(
                    "Accès interdit : matière hors périmètre (matiere_id=" + matiereId + ")");
        }
    }

    /**
     * True when the caller has unlimited scope (SUPER_ADMIN) — list endpoints
     * should not filter and should call the standard {@code findAll} path.
     */
    public boolean isUnrestricted() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (SUPER_ADMIN.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the matière ids the caller is scoped to via
     * {@code ROLE_RESPONSABLE_MATIERE:<id>} authorities. Empty if the caller
     * holds no such authority — list endpoints should treat that as "no
     * visible matières" and return an empty page. Callers must check
     * {@link #isUnrestricted()} first to know whether to skip filtering.
     */
    public Set<Long> getAccessibleMatiereIds() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Collections.emptySet();
        }
        Set<Long> ids = new HashSet<>();
        for (GrantedAuthority a : auth.getAuthorities()) {
            String authority = a.getAuthority();
            if (authority != null && authority.startsWith(RESP_PREFIX)) {
                String idPart = authority.substring(RESP_PREFIX.length());
                try {
                    ids.add(Long.parseLong(idPart));
                } catch (NumberFormatException ignored) {
                    // skip malformed authority (defensive — shouldn't happen)
                }
            }
        }
        return ids;
    }
}
