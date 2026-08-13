package tn.epos.common.security.revocation;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * #306 — décorateur de {@link JwtDecoder} : une signature valide ne suffit plus, le jeton doit
 * aussi être POSTÉRIEUR à la dernière révocation de son porteur.
 *
 * <p>C'est le point d'application unique d'un resource server : le bean {@code JwtDecoder} est
 * consulté par la chaîne HTTP (oauth2ResourceServer) ET par l'intercepteur STOMP de
 * scoring-service ({@code WebSocketSecurityConfig} injecte le même bean) — envelopper ici couvre
 * donc le REST et la poignée de main WebSocket sans second mécanisme.
 *
 * <p>Le refus emprunte {@link JwtException}, le même canal qu'une signature fausse ou un jeton
 * expiré → 401. C'est voulu : pour le client, « jeton révoqué » et « jeton expiré » appellent la
 * même réaction (tenter le refresh, qui relit la base et dira la vraie raison), et un message
 * distinct n'aiderait que quelqu'un qui teste des jetons volés.
 */
public final class RevocationAwareJwtDecoder implements JwtDecoder {

    private final JwtDecoder delegate;
    private final TokenRevocationList revocationList;

    public RevocationAwareJwtDecoder(JwtDecoder delegate, TokenRevocationList revocationList) {
        this.delegate = delegate;
        this.revocationList = revocationList;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        Jwt jwt = delegate.decode(token);

        Long userId = extractUserId(jwt);
        if (revocationList.isRevoked(userId, jwt.getIssuedAt())) {
            throw new JwtException("Jeton révoqué (émis avant la dernière révocation de l'utilisateur "
                    + userId + ")");
        }
        return jwt;
    }

    /**
     * Le claim {@code userId} arrive en {@link Number} dont le type concret dépend du parseur
     * (Integer ou Long selon la valeur) — même précaution que {@code extractUserId} des
     * contrôleurs. Un jeton sans ce claim n'a pas été émis par auth-service : on le laisse
     * passer ici, la conversion d'autorités en aval le rendra inoffensif.
     */
    private Long extractUserId(Jwt jwt) {
        Object claim = jwt.getClaim("userId");
        return (claim instanceof Number number) ? number.longValue() : null;
    }
}
