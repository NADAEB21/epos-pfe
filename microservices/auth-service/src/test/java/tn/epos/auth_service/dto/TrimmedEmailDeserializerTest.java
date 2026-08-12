package tn.epos.auth_service.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ergonomie — une adresse collée avec un espace ne doit plus être refusée.
 *
 * <p>Le défaut : une adresse copiée depuis un tableur ou un courriel traîne un espace, et
 * {@code @Email} la refusait en <b>400 « adresse invalide »</b> pour une adresse parfaitement
 * valide. L'utilisateur ne voyait pas ce qui clochait.
 *
 * <p>Ces tests passent par le VRAI {@link ObjectMapper} puis par le VRAI validateur, parce que
 * tout l'intérêt est dans l'ORDRE : Jackson désérialise (et donc trime) avant que Bean Validation
 * ne s'exécute. Un test qui appellerait le désérialiseur à la main ne prouverait pas ça — et c'est
 * précisément ce qui était cassé.
 */
@DisplayName("Adresse e-mail collée : le trim précède la validation")
class TrimmedEmailDeserializerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    private LoginRequest lireLogin(String json) throws Exception {
        return mapper.readValue(json, LoginRequest.class);
    }

    @Test
    @DisplayName("Espaces autour de l'adresse : retirés, et la validation passe")
    void espacesRetires_validationPasse() throws Exception {
        LoginRequest r = lireLogin("{\"email\":\"  admin@epos.tn  \",\"password\":\"Admin@1234\"}");

        assertThat(r.getEmail()).isEqualTo("admin@epos.tn");
        assertThat(validator.validate(r))
                .as("c'est ce 400 que le ticket d'ergonomie visait")
                .isEmpty();
    }

    @Test
    @DisplayName("Tabulation et retour à la ligne comptent aussi (collage depuis un tableur)")
    void tabulationEtRetourLigne() throws Exception {
        LoginRequest r = lireLogin("{\"email\":\"\\t admin@epos.tn \\n\",\"password\":\"x\"}");
        assertThat(r.getEmail()).isEqualTo("admin@epos.tn");
    }

    /**
     * Une saisie qui ne contient QUE des espaces devient vide, donc {@code @NotBlank} la refuse —
     * avec le bon message (« ne doit pas être vide ») plutôt que « adresse invalide ».
     */
    @Test
    @DisplayName("Que des espaces : refusé par @NotBlank, pas par @Email")
    void queDesEspaces_refuseParNotBlank() throws Exception {
        LoginRequest r = lireLogin("{\"email\":\"   \",\"password\":\"x\"}");

        assertThat(r.getEmail()).isEmpty();
        assertThat(validator.validate(r)).isNotEmpty();
    }

    /**
     * ⚠️ Le garde-fou du choix de conception : on ne trime QUE l'adresse. Un espace de bord dans un
     * mot de passe est un caractère légitime, et le supprimer en silence rendrait un mot de passe
     * correct impossible à saisir. C'est pour ça qu'il n'y a pas de trim global sur les chaînes.
     */
    @Test
    @DisplayName("Le mot de passe n'est PAS trimé — un espace y est un caractère légitime")
    void motDePasse_nonTrime() throws Exception {
        LoginRequest r = lireLogin("{\"email\":\"admin@epos.tn\",\"password\":\"  secret  \"}");
        assertThat(r.getPassword()).isEqualTo("  secret  ");
    }

    @Test
    @DisplayName("Une adresse réellement invalide reste refusée")
    void adresseInvalide_resteRefusee() throws Exception {
        LoginRequest r = lireLogin("{\"email\":\"  pas une adresse  \",\"password\":\"x\"}");
        assertThat(validator.validate(r)).isNotEmpty();
    }

    @Test
    @DisplayName("email absent → null, sans exception")
    void emailAbsent() throws Exception {
        LoginRequest r = lireLogin("{\"password\":\"x\"}");
        assertThat(r.getEmail()).isNull();
    }

    @Test
    @DisplayName("La demande de réinitialisation bénéficie du même trim")
    void passwordReset_memeTrim() throws Exception {
        PasswordResetRequestDto d = mapper.readValue(
                "{\"email\":\" Resp@epos.tn \"}", PasswordResetRequestDto.class);

        assertThat(d.getEmail()).isEqualTo("Resp@epos.tn");
        assertThat(validator.validate(d)).isEmpty();
    }
}
