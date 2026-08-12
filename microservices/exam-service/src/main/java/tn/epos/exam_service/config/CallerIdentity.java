package tn.epos.exam_service.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Qui appelle — l'identité de l'appelant, lue depuis le claim JWT {@code userId}.
 *
 * <p><b>Pourquoi cette classe existe.</b> exam-service savait dire ce qu'un appelant a le DROIT
 * de faire ({@link MatiereAccessChecker}, qui lit des autorités) mais pas QUI il est : aucun
 * extracteur d'identité n'existait dans le service. Or « le conducteur d'une épreuve » se
 * constate — c'est l'auteur du dernier acte de conduite — et sans nom d'auteur, le système sait
 * QUAND une épreuve a été lancée ({@code launched_at}) mais jamais PAR QUI.
 *
 * <p>Miroir volontaire de {@code scoring-service EvaluateurScopeChecker.getCallerUserId()} :
 * mêmes règles, même tolérance sur le type du claim. auth-service émet
 * {@code claim("userId", user.getId())} ; selon le décodeur, un entier JSON peut arriver en
 * {@link Integer} ou en {@link Long}, d'où la normalisation par {@link Number#longValue()}.
 *
 * <p>Rend {@code null} plutôt que de lever : l'appelant décide. Une attribution manquante ne doit
 * jamais faire échouer un acte métier — on préfère un {@code lance_par} nul, honnête, à un
 * lancement refusé parce que le jeton était atypique.
 */
@Component
public class CallerIdentity {

    private static final String USER_ID_CLAIM = "userId";

    /** L'identifiant de l'appelant, ou {@code null} si aucun JWT exploitable. */
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
}
