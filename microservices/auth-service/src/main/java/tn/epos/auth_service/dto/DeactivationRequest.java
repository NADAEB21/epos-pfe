package tn.epos.auth_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * #289 — corps d'un retrait (ou d'une réouverture) d'accès.
 *
 * <p>Le motif est OBLIGATOIRE, exactement comme pour une suppléance
 * d'évaluateur (ADR-0017) : fermer le compte d'un collègue est un acte plus
 * lourd, il ne peut pas en demander moins. Un champ facultatif serait laissé
 * vide neuf fois sur dix et la trace ne vaudrait plus rien.
 */
public record DeactivationRequest(
        @NotBlank(message = "Le motif est obligatoire : un retrait d'accès doit pouvoir s'expliquer.")
        @Size(max = 500, message = "Le motif ne peut pas dépasser 500 caractères.")
        String motif
) {}
