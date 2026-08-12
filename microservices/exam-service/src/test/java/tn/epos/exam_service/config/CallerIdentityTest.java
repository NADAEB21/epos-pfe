package tn.epos.exam_service.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #306 — l'identité de l'appelant, pendant de l'horodatage des actes de conduite.
 *
 * <p>Miroir de {@code scoring-service EvaluateurScopeCheckerTest} pour la partie
 * {@code getCallerUserId} : mêmes cas, parce que c'est le même contrat de claim et que deux
 * lectures divergentes du même JWT finiraient par attribuer deux auteurs différents au même acte.
 *
 * <p>Le fil conducteur : <b>cette classe ne lève jamais</b>. Une attribution manquante ne doit pas
 * faire échouer un lancement — on préfère un {@code lance_par} nul et honnête à une épreuve
 * refusée parce que le jeton était atypique.
 */
@DisplayName("CallerIdentity — qui agit (#306)")
class CallerIdentityTest {

    private final CallerIdentity identity = new CallerIdentity();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** JWT réel, comme le monte le resource server. */
    private void authAsJwt(Object userIdClaim) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("u@epos.tn");
        if (userIdClaim != null) {
            builder.claim("userId", userIdClaim);
        } else {
            // un Jwt exige au moins un claim : on en met un qui n'est PAS userId
            builder.claim("scope", "read");
        }
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(builder.build(), List.of()));
    }

    @Test
    @DisplayName("claim Long → l'identifiant")
    void claimLong() {
        authAsJwt(42L);
        assertThat(identity.getCallerUserId()).isEqualTo(42L);
    }

    /**
     * Le cas qui justifie la normalisation par {@code Number#longValue()} : selon le décodeur, un
     * entier JSON arrive en {@link Integer} ou en {@link Long}. Sans ce test, une attribution
     * silencieusement nulle en production serait indétectable.
     */
    @Test
    @DisplayName("claim Integer → normalisé en Long")
    void claimInteger() {
        authAsJwt(7);
        assertThat(identity.getCallerUserId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("claim String numérique → parsé, espaces tolérés")
    void claimStringNumerique() {
        authAsJwt(" 13 ");
        assertThat(identity.getCallerUserId()).isEqualTo(13L);
    }

    @Test
    @DisplayName("claim String illisible → null, jamais d'exception")
    void claimStringIllisible() {
        authAsJwt("abc");
        assertThat(identity.getCallerUserId()).isNull();
    }

    @Test
    @DisplayName("claim userId absent → null")
    void claimAbsent() {
        authAsJwt(null);
        assertThat(identity.getCallerUserId()).isNull();
    }

    @Test
    @DisplayName("principal non-JWT → null, pas de ClassCastException")
    void principalNonJwt() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u", "p", List.of()));
        assertThat(identity.getCallerUserId()).isNull();
    }

    @Test
    @DisplayName("aucune authentification → null")
    void sansAuthentification() {
        SecurityContextHolder.clearContext();
        assertThat(identity.getCallerUserId()).isNull();
    }
}
