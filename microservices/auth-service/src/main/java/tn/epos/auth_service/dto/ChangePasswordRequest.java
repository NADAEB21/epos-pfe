package tn.epos.auth_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Corps de PUT /api/v1/auth/change-password.
 *
 * Distinct de {@link PasswordResetConfirmDto} : ce flux exige le mot de passe
 * actuel (utilisateur déjà authentifié, changement volontaire depuis l'écran
 * Profil) alors que la réinitialisation par email ne connaît pas l'ancien mot
 * de passe (l'utilisateur l'a oublié) et s'appuie sur un token à usage unique
 * envoyé par email.
 */
@Getter
@NoArgsConstructor
public class ChangePasswordRequest {

    @NotBlank(message = "Le mot de passe actuel est obligatoire")
    private String currentPassword;

    @NotBlank(message = "Le nouveau mot de passe est obligatoire")
    @Size(min = 8, message = "Le nouveau mot de passe doit contenir au moins 8 caractères")
    @Pattern(
            regexp = "^(?=.*[A-Z])(?=.*\\d).+$",
            message = "Le nouveau mot de passe doit contenir au moins une majuscule et un chiffre"
    )
    private String newPassword;
}
