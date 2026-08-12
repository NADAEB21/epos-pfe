package tn.epos.auth_service.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #285 — l'e-mail est canonicalisé à chaque écriture de l'entité.
 *
 * <p><b>Le défaut s'est produit pour de vrai.</b> Constaté en base le 2026-08-12 : deux comptes
 * pour la même adresse — {@code s34-eval@epos.tn} et {@code S34-EVAL@EPOS.TN} — donc deux jeux de
 * rôles et deux pistes d'audit pour une seule personne, sans rien qui signale qu'il s'agit du même
 * être humain. Une faute de frappe sur la casse suffisait.
 *
 * <p>On teste les hooks JPA en les appelant directement par réflexion : ils sont {@code protected}
 * (contrat Hibernate) et ce test ne veut pas d'un contexte Spring ni d'une base pour vérifier une
 * règle de trois lignes. Ce que la réflexion ne prouve pas — que Hibernate les déclenche
 * réellement — est couvert par la vérification en direct sur la pile, et par l'index
 * {@code uq_users_email_lower} (V5) qui refuserait le doublon même si le hook sautait.
 */
@DisplayName("User — e-mail canonique (#285)")
class UserEmailCanoniqueTest {

    private static void declencher(User u, String hook) throws Exception {
        Method m = User.class.getDeclaredMethod(hook);
        m.setAccessible(true);
        m.invoke(u);
    }

    private static User avecEmail(String email) {
        User u = new User();
        u.setEmail(email);
        return u;
    }

    @Test
    @DisplayName("À la création : les majuscules sont ramenées en minuscules")
    void creation_majuscules() throws Exception {
        User u = avecEmail("S34-EVAL@EPOS.TN");
        declencher(u, "onCreate");
        assertThat(u.getEmail()).isEqualTo("s34-eval@epos.tn");
    }

    /**
     * Le cas exact du doublon trouvé en base : deux saisies de la même adresse, l'une avec une
     * majuscule initiale, doivent produire la MÊME valeur stockée — donc entrer en collision au
     * lieu de créer un second compte.
     */
    @Test
    @DisplayName("Deux casses de la même adresse convergent vers la même valeur")
    void deuxCasses_memeValeur() throws Exception {
        User a = avecEmail("Admin@Epos.TN");
        User b = avecEmail("admin@epos.tn");
        declencher(a, "onCreate");
        declencher(b, "onCreate");
        assertThat(a.getEmail()).isEqualTo(b.getEmail());
    }

    @Test
    @DisplayName("À la modification aussi — un changement d'adresse ne rouvre pas la faille")
    void modification_canonicalise() throws Exception {
        User u = avecEmail("admin@epos.tn");
        declencher(u, "onCreate");
        u.setEmail("Nouveau.Nom@EPOS.TN");
        declencher(u, "onUpdate");
        assertThat(u.getEmail()).isEqualTo("nouveau.nom@epos.tn");
    }

    /**
     * Les espaces autour d'une adresse collée depuis un tableur sont une cause banale de
     * « compte introuvable ». Ils sont retirés à la même occasion.
     */
    @Test
    @DisplayName("Les espaces de bord sont retirés (adresse collée depuis un fichier)")
    void espacesRetires() throws Exception {
        User u = avecEmail("  Resp@epos.tn \t");
        declencher(u, "onCreate");
        assertThat(u.getEmail()).isEqualTo("resp@epos.tn");
    }

    @Test
    @DisplayName("Un e-mail null ne fait pas exploser le hook")
    void emailNull_neLevePas() throws Exception {
        User u = new User();
        declencher(u, "onCreate");
        assertThat(u.getEmail()).isNull();
    }
}
