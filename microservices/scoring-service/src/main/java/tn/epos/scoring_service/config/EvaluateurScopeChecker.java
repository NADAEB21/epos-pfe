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
 * <p><b>#274 — un seul booléen ne peut pas servir deux sens.</b> Cette classe exposait un
 * {@code isUnrestricted()} unique, vrai pour {@code SUPER_ADMIN} <b>et</b>
 * {@code RESPONSABLE_MATIERE}, consommé à la fois par les filtres de LISTE et par les gardes
 * d'ÉCRITURE. Conséquence mesurée : un responsable — de <i>n'importe quelle</i> matière —
 * traversait {@link #checkOwnership(Long)} avant la moindre comparaison d'identité, et écrasait
 * la note d'un évaluateur par {@code PUT /api/notation-items/{id}} sans motif ni attribution,
 * alors que la porte {@code POST /evaluateur/notations/saisir} (garde #213) refusait le même
 * appelant. Le booléen est donc scindé en deux méthodes qui NOMMENT leur sens :
 * {@link #peutLireHorsPerimetre()} et {@link #peutEcrireHorsPerimetre()}.
 *
 * <p>Le canal du responsable vers une note est le <b>réajustement audité</b> — motivé, attribué,
 * historisé (ADR-0013 partie 2), et déjà le seul que l'IHM web utilise. L'écriture directe et
 * silencieuse n'était pas une fonctionnalité, c'était l'absence d'une garde.
 *
 * <p>Le périmètre par <b>matière</b> ne vit pas ici : voir
 * {@code MatiereScopeChecker} / {@code MatiereAccessGuard}. Les deux gardes se composent, et
 * c'est voulu — la plus restrictive gagne pour qui porte les deux rôles.
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
     * L'appelant peut-il <b>LIRE</b> au-delà de ses propres rotations —
     * {@code SUPER_ADMIN} ou {@code RESPONSABLE_MATIERE} ?
     *
     * <p>Réservé aux filtres de liste, qui court-circuitent le filtrage quand c'est vrai. La
     * supervision exige la vue : « accéder aux données est une LECTURE » (ADR-0018 D5).
     *
     * <p>⚠️ Ce n'est PAS un périmètre de matière : un responsable voit encore ici les notations
     * d'autres matières. Borner les LISTES demande de remonter {@code Notation → … → Lot} pour
     * chaque ligne ; c'est un chantier distinct, volontairement hors de #274, qui traite « qui a
     * le droit d'AGIR ».
     */
    public boolean peutLireHorsPerimetre() {
        return aAutorite(SUPER_ADMIN) || aAutorite(RESPONSABLE);
    }

    /**
     * L'appelant peut-il <b>ÉCRIRE</b> sur une notation qui n'est pas la sienne ?
     *
     * <p>{@code RESPONSABLE_MATIERE} ne passe plus : c'est le correctif de #274. Son canal est le
     * réajustement audité, pas un {@code PUT} silencieux.
     *
     * <p>⚠️ {@code SUPER_ADMIN} passe encore, et ce n'est pas un endossement : ADR-0018 D5 lui
     * refuse toute écriture pédagogique, divergence connue portant sur 71 points d'entrée et
     * traitée dans son propre chantier. #274 ne change qu'UN acteur, pour que sa passe de
     * régression reste lisible — un PR d'autorisation qui déplace deux acteurs à la fois ne se
     * relit pas.
     */
    public boolean peutEcrireHorsPerimetre() {
        return aAutorite(SUPER_ADMIN);
    }

    private boolean aAutorite(String attendue) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (attendue.equals(a.getAuthority())) {
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
     * L'appelant peut-il agir sur une ressource détenue par {@code evaluateurId} ?
     *
     * <p>Sémantique d'ÉCRITURE : elle s'appuie sur {@link #peutEcrireHorsPerimetre()}. Un
     * évaluateur ne peut que lorsque le détenteur résolu est lui-même. Un détenteur {@code null}
     * (chaîne de rotation rompue ou non affectée) n'est accessible à personne de borné.
     *
     * <p>Les filtres de liste appellent aussi cette méthode par ligne, mais seulement <b>après</b>
     * que {@link #peutLireHorsPerimetre()} les a laissés passer — un responsable n'atteint donc
     * jamais ce point en lecture.
     */
    public boolean isCaller(Long evaluateurId) {
        if (peutEcrireHorsPerimetre()) {
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
