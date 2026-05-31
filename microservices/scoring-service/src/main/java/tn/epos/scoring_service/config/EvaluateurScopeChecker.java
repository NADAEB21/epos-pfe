package tn.epos.scoring_service.config;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Enforces évaluateur ownership on notation/rotation access (ADR 0007, #85, #91).
 *
 * <p>An évaluateur is a GLOBAL role with no matière scope. Their effective
 * scope is the set of rotations whose {@code Rotation.evaluateurId} equals
 * their own user id. Ownership is resolved from the JWT {@code userId} claim
 * (auth-service {@code JwtService} emits {@code claim("userId", user.getId())}),
 * NOT from any authority string.
 *
 * <p>{@code SUPER_ADMIN} and {@code RESPONSABLE_MATIERE} are unrestricted here
 * (legitimate cross-examiner corrections and oversight). Only a pure
 * {@code EVALUATEUR} is constrained to their own rotations.
 *
 * <p>This is pure authorization logic: it knows nothing about the
 * {@code Notation → RotationAssignment → Rotation} chain. The services walk
 * that chain (they already hold the loaded entities) and hand the resolved
 * {@code evaluateurId} to {@link #isCaller(Long)} / {@link #checkOwnership(Long)}.
 * Mirrors the service-layer placement of exam-service {@code MatiereAccessChecker};
 * see ADR 0007 for why the checker does not resolve the chain itself.
 */
@Component
public class EvaluateurScopeChecker {

    private static final String SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final String RESPONSABLE = "ROLE_RESPONSABLE_MATIERE";
    private static final String USER_ID_CLAIM = "userId";

    /**
     * True when the caller is not constrained to their own rotations —
     * {@code SUPER_ADMIN} or {@code RESPONSABLE_MATIERE}. List endpoints skip
     * filtering and write endpoints skip the ownership check when this is true.
     */
    public boolean isUnrestricted() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            String authority = a.getAuthority();
            if (SUPER_ADMIN.equals(authority) || RESPONSABLE.equals(authority)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The caller's user id from the JWT {@code userId} claim, or {@code null}
     * when there is no authenticated JWT principal or the claim is absent.
     * Numeric JSON claims may deserialize as any {@link Number}, so the value
     * is normalised via {@link Number#longValue()}.
     */
    public Long getCallerUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        Object claim = jwt.getClaim(USER_ID_CLAIM);
        if (claim instanceof Number n) {
            return n.longValue();
        }
        if (claim instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * True when the caller may act on a resource owned by {@code evaluateurId}.
     * Unrestricted callers always may. A pure évaluateur may only when the
     * resolved owner equals their own user id. A {@code null} owner (broken or
     * unassigned rotation chain) is never accessible to a constrained caller.
     */
    public boolean isCaller(Long evaluateurId) {
        if (isUnrestricted()) {
            return true;
        }
        Long callerId = getCallerUserId();
        return callerId != null && callerId.equals(evaluateurId);
    }

    /**
     * Throws {@link AccessDeniedException} (mapped to 403 by the
     * GlobalExceptionHandler) when the caller may not act on a resource owned
     * by {@code evaluateurId}.
     */
    public void checkOwnership(Long evaluateurId) {
        if (!isCaller(evaluateurId)) {
            throw new AccessDeniedException(
                    "Acces interdit : notation hors perimetre de l'evaluateur");
        }
    }
}
