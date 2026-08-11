package tn.epos.scoring_service.config;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Borne un {@code RESPONSABLE_MATIERE} au périmètre de SES matières (#274).
 *
 * <p><b>Le défaut que cette classe corrige.</b> {@link EvaluateurScopeChecker} traitait tout
 * porteur du rôle responsable comme « non borné », sur la forme <b>nue</b>
 * {@code ROLE_RESPONSABLE_MATIERE} — sans jamais comparer la matière. Un responsable de
 * Toxicologie pouvait donc ouvrir une vague, démarrer un lot et écraser une note d'une épreuve
 * de Chimie thérapeutique. Vérifié en direct sur #274 : {@code POST /evaluateur/notations/saisir}
 * répondait 403 (garde #213, stricte) tandis que {@code PUT /api/notation-items/152} répondait
 * 200 et faisait passer la note de 8 à 3, pour le <b>même</b> appelant.
 *
 * <p><b>Ce qu'on compare.</b> auth-service émet {@code ROLE_RESPONSABLE_MATIERE:<matiere_id>} et
 * {@code tn.epos.common.security.ScopedAuthoritiesConverter} (enregistré dans
 * {@code SecurityConfig}) accorde les DEUX formes : la nue, pour que {@code hasRole} matche, et
 * la portée. Le contrôle par matière lit donc la forme <b>portée</b>, celle que
 * {@code hasAnyRole} ne peut pas exprimer.
 *
 * <p><b>Co-responsabilité.</b> Une matière peut avoir plusieurs titulaires : chacun porte sa
 * propre autorité portée sur la même matière, donc tous passent. C'est voulu — libre à
 * l'INTÉRIEUR de la matière, fermé ENTRE matières. Exiger une passation formelle entre
 * co-responsables casserait le cas « le premier rentre chez lui, le second reprend l'épreuve ».
 *
 * <p><b>Écriture seulement.</b> Cette classe ne porte volontairement pas de jumeau
 * {@code canRead}. Les lectures examen-clé réutilisent {@link #checkAccess(Long)} — c'est le
 * précédent d'exam-service, dont {@code ExamenServiceImpl.trouverParId} applique le niveau
 * écriture sur un GET. Le filtrage par matière des <b>listes</b> de notations reste hors
 * périmètre : il exige de remonter {@code Notation → … → Lot} pour chaque ligne, et c'est un
 * chantier à part.
 *
 * <p>Miroir de {@code exam-service MatiereAccessChecker}, y compris son placement en couche
 * service : la matière n'est pas dans le corps de requête, elle se résout depuis l'entité
 * déjà chargée. Aucune expression SpEL ne peut donc la porter.
 */
@Component
public class MatiereScopeChecker {

    private static final String SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final String RESP_PREFIX = "ROLE_RESPONSABLE_MATIERE:";

    /**
     * L'appelant peut-il écrire sur une ressource appartenant à {@code matiereId} ?
     *
     * <p>Échec FERMÉ sur {@code matiereId == null} : une matière irrésoluble (chaîne
     * d'entités rompue, examen inconnu) n'autorise personne, y compris un super-admin.
     * C'est le même choix que {@code EvaluateurScopeChecker.isCaller(null)}.
     *
     * <p>⚠️ {@code SUPER_ADMIN} passe encore ici. C'est la divergence connue ADR-0018 D5 —
     * un administrateur n'a pas d'autorité pédagogique — traitée dans son propre chantier
     * (les 71 points d'écriture qui le nomment). Ce n'est pas un endossement : #274 ne change
     * qu'UN acteur, pour que sa passe de régression reste lisible.
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
     * Lève {@link AccessDeniedException} (mappée en 403 par le
     * {@code GlobalExceptionHandler}) quand la matière est hors du périmètre de l'appelant.
     */
    public void checkAccess(Long matiereId) {
        if (!canAccess(matiereId)) {
            throw new AccessDeniedException(
                    "Accès interdit : matière hors périmètre (matiere_id=" + matiereId + ")");
        }
    }
}
