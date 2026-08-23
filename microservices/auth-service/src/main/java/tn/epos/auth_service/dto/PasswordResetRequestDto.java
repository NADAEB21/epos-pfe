package tn.epos.auth_service.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PasswordResetRequestDto {

    @NotBlank
    @Email
    // Ergonomie : une adresse collee ne doit pas faire echouer la demande en silence.
    // Le trim a lieu a la DESERIALISATION, donc avant @Email/@NotBlank.
    @JsonDeserialize(using = TrimmedEmailDeserializer.class)
    private String email;
}
