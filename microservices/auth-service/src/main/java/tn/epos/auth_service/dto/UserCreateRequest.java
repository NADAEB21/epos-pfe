package tn.epos.auth_service.dto;

import jakarta.validation.Valid;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class UserCreateRequest {

    @NotBlank
    @Email
    // Ergonomie : creer un compte avec une adresse collee ne doit pas repondre 400.
    // Le trim a lieu a la DESERIALISATION, donc avant @Email/@NotBlank.
    @JsonDeserialize(using = TrimmedEmailDeserializer.class)
    private String email;

    /**
     * #389 — OPTIONNEL. Absent ou vide : le serveur pose un mot de passe jetable
     * aléatoire, jamais rendu ni envoyé — la personne choisit le sien via le
     * lien d'invitation reçu par e-mail. Présent : la politique s'applique
     * (min 8, une majuscule, un chiffre — la même que PasswordResetConfirmDto).
     */
    @Pattern(
        regexp = "^$|^(?=.*[A-Z])(?=.*\\d).{8,}$",
        message = "Password must be at least 8 characters, with at least one uppercase letter and one digit"
    )
    private String password;

    @NotBlank
    private String nom;

    @NotBlank
    private String prenom;

    @NotEmpty
    @Valid
    private List<RoleAssignmentDto> roles;
}
