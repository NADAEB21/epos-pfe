package tn.epos.exam_service.config;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

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
}
