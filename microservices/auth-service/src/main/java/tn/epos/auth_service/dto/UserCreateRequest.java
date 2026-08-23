package tn.epos.auth_service.dto;

import jakarta.validation.Valid;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    @NotBlank
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Pattern(
        regexp = "^(?=.*[A-Z])(?=.*\\d).+$",
        message = "Password must contain at least one uppercase letter and one digit"
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
