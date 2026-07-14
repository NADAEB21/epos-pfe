package tn.epos.auth_service.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contrat JSON + politique de mot de passe de {@link ChangePasswordRequest} (PR #180).
 *
 * <p>On désérialise du VRAI JSON — comme le fait le contrôleur — plutôt que de mocker le
 * DTO : un mock court-circuite les getters et la validation, donc il ne prouve ni que le
 * corps de requête se lie correctement, ni que la politique de mot de passe mord.
 *
 * <p>La politique doit rester alignée sur celle de la création d'utilisateur :
 * ≥ 8 caractères, au moins une majuscule et un chiffre.
 */
class ChangePasswordRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Validator validator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator();
        }
    }

    private ChangePasswordRequest parse(String json) throws Exception {
        return MAPPER.readValue(json, ChangePasswordRequest.class);
    }

    @Test
    void devraitLierLeCorpsJson() throws Exception {
        ChangePasswordRequest req = parse(
                "{\"currentPassword\":\"Eval@1234\",\"newPassword\":\"NewPass@99\"}");

        assertThat(req.getCurrentPassword()).isEqualTo("Eval@1234");
        assertThat(req.getNewPassword()).isEqualTo("NewPass@99");
        assertThat(validator().validate(req)).isEmpty();
    }

    @Test
    void devraitIgnorerLesChampsInconnus_commeUnUserIdInjecte() throws Exception {
        // Le corps ne doit JAMAIS pouvoir désigner la victime : l'userId vient du JWT.
        // Jackson est configuré pour ne pas exploser, et le DTO n'a tout simplement pas
        // de champ userId à lier.
        ChangePasswordRequest req = MAPPER
                .copy()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature
                        .FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue("{\"userId\":999,\"currentPassword\":\"Eval@1234\","
                        + "\"newPassword\":\"NewPass@99\"}", ChangePasswordRequest.class);

        assertThat(req.getCurrentPassword()).isEqualTo("Eval@1234");
        assertThat(req.getNewPassword()).isEqualTo("NewPass@99");
    }

    @Test
    void motDePasseTropCourt_devraitEtreInvalide() throws Exception {
        ChangePasswordRequest req = parse(
                "{\"currentPassword\":\"Eval@1234\",\"newPassword\":\"Ab1\"}");

        assertThat(validator().validate(req)).isNotEmpty();
    }

    @Test
    void motDePasseSansMajuscule_devraitEtreInvalide() throws Exception {
        ChangePasswordRequest req = parse(
                "{\"currentPassword\":\"Eval@1234\",\"newPassword\":\"motdepasse1\"}");

        assertThat(validator().validate(req)).isNotEmpty();
    }

    @Test
    void motDePasseSansChiffre_devraitEtreInvalide() throws Exception {
        ChangePasswordRequest req = parse(
                "{\"currentPassword\":\"Eval@1234\",\"newPassword\":\"MotDePasseLong\"}");

        assertThat(validator().validate(req)).isNotEmpty();
    }

    @Test
    void motDePasseActuelVide_devraitEtreInvalide() throws Exception {
        ChangePasswordRequest req = parse(
                "{\"currentPassword\":\"\",\"newPassword\":\"NewPass@99\"}");

        assertThat(validator().validate(req)).isNotEmpty();
    }
}
