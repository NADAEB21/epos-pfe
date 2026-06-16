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
 * <p>Write operations (create / modify / delete) require SUPER_ADMIN or a
 * scoped RESPONSABLE_MATIERE authority matching the resource's matiereId.
 * Use {@link #checkAccess(Long)} for these.
 *
 * <p>Read operations (GET grille, GET station detail) also allow EVALUATEUR,
 * because an evaluator must load the grading grid for their assigned station
 * during an exam session. Use {@link #checkReadAccess(Long)} for these.
 */
@Component
public class MatiereAccessChecker {

    private static final String SUPER_ADMIN    = "ROLE_SUPER_ADMIN";
    private static final String RESP_PREFIX    = "ROLE_RESPONSABLE_MATIERE:";
    private static final String EVALUATEUR     = "ROLE_EVALUATEUR";

    // -------------------------------------------------------------------------
    // Write-level access  (SUPER_ADMIN | RESPONSABLE_MATIERE:<matiereId>)
    // -------------------------------------------------------------------------

    /**
     * Returns true when the caller may perform write operations on a resource
     * belonging to {@code matiereId}.
     */
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

    /**
     * Throws {@link AccessDeniedException} when the caller may not write to a
     * resource belonging to {@code matiereId}.
     */
    public void checkAccess(Long matiereId) {
        if (!canAccess(matiereId)) {
            throw new AccessDeniedException(
                    "Accès interdit : matière hors périmètre (matiere_id=" + matiereId + ")");
        }
    }

    // -------------------------------------------------------------------------
    // Read-level access  (SUPER_ADMIN | RESPONSABLE_MATIERE:<matiereId> | EVALUATEUR)
    // -------------------------------------------------------------------------

    /**
     * Returns true when the caller may perform read operations on a resource
     * belonging to {@code matiereId}. EVALUATEUR is granted read access to all
     * grilles so they can load the grading grid for their assigned station.
     */
    public boolean canRead(Long matiereId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || matiereId == null) {
            return false;
        }
        String scoped = RESP_PREFIX + matiereId;
        for (GrantedAuthority a : auth.getAuthorities()) {
            String authority = a.getAuthority();
            if (SUPER_ADMIN.equals(authority)
                    || scoped.equals(authority)
                    || EVALUATEUR.equals(authority)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Throws {@link AccessDeniedException} when the caller may not read a
     * resource belonging to {@code matiereId}.
     */
    public void checkReadAccess(Long matiereId) {
        if (!canRead(matiereId)) {
            throw new AccessDeniedException(
                    "Accès interdit : matière hors périmètre (matiere_id=" + matiereId + ")");
        }
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    /**
     * True when the caller has unlimited scope (SUPER_ADMIN). List endpoints
     * should skip filtering and call the standard {@code findAll} path.
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
     * Returns the matiereIds the caller is scoped to via
     * {@code ROLE_RESPONSABLE_MATIERE:<id>} authorities. Empty if the caller
     * holds no such authority. Callers must check {@link #isUnrestricted()}
     * first to know whether to skip filtering.
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
                    // defensive — should never happen with well-formed tokens
                }
            }
        }
        return ids;
    }
}