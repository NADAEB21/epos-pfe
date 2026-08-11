package tn.epos.auth_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * #134 — création ou renommage d'une matière du catalogue.
 *
 * <p>Le code est la référence courte affichée dans les listes (ex. CHIM_THER) ;
 * le libellé est le nom complet (ex. « Chimie thérapeutique »). L'unicité du
 * code est vérifiée SANS tenir compte de la casse : « chim_ther » et
 * « CHIM_THER » seraient deux lignes pour la même matière (même piège que
 * l'unicité d'e-mail, #285).
 */
public record MatiereRequest(
        @NotBlank(message = "Le code est obligatoire.")
        @Size(max = 20, message = "Le code ne peut pas dépasser 20 caractères.")
        String code,

        @NotBlank(message = "Le libellé est obligatoire.")
        @Size(max = 100, message = "Le libellé ne peut pas dépasser 100 caractères.")
        String libelle
) {}
