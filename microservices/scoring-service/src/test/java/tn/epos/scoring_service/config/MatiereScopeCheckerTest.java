package tn.epos.scoring_service.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #274 — périmètre de matière dans scoring-service.
 *
 * <p>Miroir de {@code exam-service MatiereAccessCheckerTest} : la classe testée n'a aucune
 * dépendance injectée, elle lit {@code SecurityContextHolder}. Un {@code new} suffit, pas de
 * contexte Spring, pas de Mockito.
 *
 * <p>⚠️ Chaque test pose les DEUX formes d'autorité — {@code ROLE_RESPONSABLE_MATIERE} et
 * {@code ROLE_RESPONSABLE_MATIERE:5} — parce que c'est ce que produit
 * {@code ScopedAuthoritiesConverter} en production. Ne poser que la forme nue testerait une
 * situation qui n'existe pas.
 */
@DisplayName("MatiereScopeChecker — périmètre par matière (#274)")
class MatiereScopeCheckerTest {

    private final MatiereScopeChecker checker = new MatiereScopeChecker();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authWith(String... authorities) {
        var grants = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u", "p", grants));
    }

    // ── Le cœur du ticket ───────────────────────────────────────────────────

    @Test
    @DisplayName("RESPONSABLE_MATIERE:5 accède à la matière 5")
    void responsable_saMatiere() {
        authWith("ROLE_RESPONSABLE_MATIERE", "ROLE_RESPONSABLE_MATIERE:5");
        assertThat(checker.canAccess(5L)).isTrue();
    }

    @Test
    @DisplayName("#274 — RESPONSABLE_MATIERE:5 n'accède PAS à la matière 7")
    void responsable_autreMatiere_refuse() {
        authWith("ROLE_RESPONSABLE_MATIERE", "ROLE_RESPONSABLE_MATIERE:5");
        assertThat(checker.canAccess(7L))
                .as("un responsable de Toxicologie n'a rien à faire dans une épreuve de Chimie")
                .isFalse();
    }

    @Test
    @DisplayName("Co-responsabilité : plusieurs portées passent, chacune sur la sienne")
    void responsable_plusieursPortees() {
        authWith("ROLE_RESPONSABLE_MATIERE",
                "ROLE_RESPONSABLE_MATIERE:5", "ROLE_RESPONSABLE_MATIERE:7");
        assertThat(checker.canAccess(5L)).isTrue();
        assertThat(checker.canAccess(7L)).isTrue();
        assertThat(checker.canAccess(9L)).isFalse();
    }

    /**
     * Le piège que la comparaison par préfixe doit éviter : {@code "…:5"} ne doit pas satisfaire
     * une demande sur la matière 55 (ni l'inverse). La comparaison est une ÉGALITÉ de chaîne
     * complète, pas un {@code startsWith}.
     */
    @Test
    @DisplayName("« :5 » ne satisfait pas la matière 55 (pas de collision de préfixe)")
    void responsable_pasDeCollisionDePrefixe() {
        authWith("ROLE_RESPONSABLE_MATIERE", "ROLE_RESPONSABLE_MATIERE:5");
        assertThat(checker.canAccess(55L)).isFalse();
        authWith("ROLE_RESPONSABLE_MATIERE", "ROLE_RESPONSABLE_MATIERE:55");
        assertThat(checker.canAccess(5L)).isFalse();
    }

    // ── Les autres acteurs ──────────────────────────────────────────────────

    /**
     * ADR-0018 D5 lui refuse toute écriture pédagogique — divergence connue, portée par son
     * propre chantier (les 71 points d'entrée qui le nomment). Ce test documente l'état ACTUEL
     * pour qu'un changement futur soit délibéré et non accidentel.
     */
    @Test
    @DisplayName("SUPER_ADMIN passe partout (divergence ADR-0018 D5 assumée et différée)")
    void superAdmin_partout() {
        authWith("ROLE_SUPER_ADMIN");
        assertThat(checker.canAccess(5L)).isTrue();
        assertThat(checker.canAccess(999L)).isTrue();
    }

    @Test
    @DisplayName("Un ÉVALUATEUR n'écrit sur aucune matière (il est borné par ses rotations)")
    void evaluateur_refuse() {
        authWith("ROLE_EVALUATEUR");
        assertThat(checker.canAccess(5L)).isFalse();
    }

    @Test
    @DisplayName("La forme NUE seule ne suffit pas — c'est tout le défaut de #274")
    void roleNuSeul_refuse() {
        // Un jeton ne portant que le rôle sans portée ne désigne aucune matière. L'ancienne
        // garde acceptait précisément cette forme, et acceptait donc TOUTES les matières.
        authWith("ROLE_RESPONSABLE_MATIERE");
        assertThat(checker.canAccess(5L)).isFalse();
    }

    // ── Échecs fermés ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Sans authentification : refusé")
    void sansAuth_refuse() {
        SecurityContextHolder.clearContext();
        assertThat(checker.canAccess(5L)).isFalse();
    }

    @Test
    @DisplayName("matiereId null : refusé, MÊME pour un super-admin (échec fermé)")
    void matiereNull_refuse() {
        authWith("ROLE_SUPER_ADMIN");
        assertThat(checker.canAccess(null))
                .as("une matière irrésoluble n'autorise personne")
                .isFalse();
    }

    @Test
    @DisplayName("Une autorité malformée (« :abc ») ne fait pas passer")
    void autoriteMalformee_refuse() {
        authWith("ROLE_RESPONSABLE_MATIERE", "ROLE_RESPONSABLE_MATIERE:abc");
        assertThat(checker.canAccess(5L)).isFalse();
    }

    // ── checkAccess ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("checkAccess lève AccessDeniedException (403) et nomme la matière refusée")
    void checkAccess_leve() {
        authWith("ROLE_RESPONSABLE_MATIERE", "ROLE_RESPONSABLE_MATIERE:5");
        assertThatThrownBy(() -> checker.checkAccess(7L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("matiere_id=7");
    }

    @Test
    @DisplayName("checkAccess rend la main en silence quand c'est autorisé")
    void checkAccess_silencieux() {
        authWith("ROLE_RESPONSABLE_MATIERE", "ROLE_RESPONSABLE_MATIERE:5");
        assertThatCode(() -> checker.checkAccess(5L)).doesNotThrowAnyException();
    }
}
