package tn.epos.auth_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * #134 — corps d'un acte administratif motivé (retrait ou réouverture d'une
 * matière). Même doctrine que le retrait d'un compte (#289) : le motif est
 * OBLIGATOIRE, sans quoi la trace ne vaut rien.
 */
public record MotifRequest(
        @NotBlank(message = "Le motif est obligatoire : un acte administratif doit pouvoir s'expliquer.")
        @Size(max = 500, message = "Le motif ne peut pas dépasser 500 caractères.")
        String motif
) {}
