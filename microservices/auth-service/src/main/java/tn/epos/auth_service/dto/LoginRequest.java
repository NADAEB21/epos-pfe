package tn.epos.auth_service.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class LoginRequest {

    @NotBlank
    @Email
    // Ergonomie : se connecter avec une adresse collee (espace de bord) ne doit pas repondre 400.
    // Le trim a lieu a la DESERIALISATION, donc avant @Email/@NotBlank.
    @JsonDeserialize(using = TrimmedEmailDeserializer.class)
    private String email;

    @NotBlank
    private String password;
}
