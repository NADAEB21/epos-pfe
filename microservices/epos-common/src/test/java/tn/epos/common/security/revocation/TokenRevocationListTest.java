package tn.epos.common.security.revocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #306 — le contrat de la liste : « émis avant l'estampille = mort », rien de plus,
 * rien de moins. Chaque cas nomme le défaut qu'il empêche de revenir.
 */
class TokenRevocationListTest {

    private static final Long USER = 60L;
    private static final Instant STAMP = Instant.parse("2026-08-13T10:00:00Z");

    private final TokenRevocationList list = new TokenRevocationList();

    @Test
    @DisplayName("un jeton émis AVANT l'estampille est révoqué")
    void jetonAnterieurRevoque() {
        list.replaceAll(Map.of(USER, STAMP));
        assertThat(list.isRevoked(USER, STAMP.minusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("un jeton émis APRÈS l'estampille vit — sinon re-login impossible à jamais")
    void jetonPosterieurVit() {
        list.replaceAll(Map.of(USER, STAMP));
        assertThat(list.isRevoked(USER, STAMP.plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("un utilisateur absent de la liste n'est jamais révoqué")
    void utilisateurAbsentJamaisRevoque() {
        list.replaceAll(Map.of(USER, STAMP));
        assertThat(list.isRevoked(999L, STAMP.minusSeconds(3600))).isFalse();
    }

    @Test
    @DisplayName("un jeton SANS iat d'un utilisateur listé est révoqué — on ne prouve pas sa postériorité")
    void sansIatRevoqueSiListe() {
        list.replaceAll(Map.of(USER, STAMP));
        assertThat(list.isRevoked(USER, null)).isTrue();
    }

    @Test
    @DisplayName("un jeton sans userId (null) passe — il n'a pas été émis par auth-service")
    void sansUserIdPasse() {
        list.replaceAll(Map.of(USER, STAMP));
        assertThat(list.isRevoked(null, STAMP.minusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("replaceAll REMPLACE : une entrée disparue de l'instantané cesse de révoquer")
    void replaceAllRemplace() {
        list.replaceAll(Map.of(USER, STAMP));
        list.replaceAll(Map.of());
        assertThat(list.isRevoked(USER, STAMP.minusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("put ne recule jamais : une estampille plus ancienne ne remplace pas la plus récente")
    void putNeReculeJamais() {
        list.put(USER, STAMP);
        list.put(USER, STAMP.minusSeconds(3600));
        assertThat(list.isRevoked(USER, STAMP.minusSeconds(1))).isTrue();
    }
}
