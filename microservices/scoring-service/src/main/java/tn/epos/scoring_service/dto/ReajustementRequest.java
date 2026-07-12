package tn.epos.scoring_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/notations/{id}/reajustement} (ADR-0013 Part 2).
 *
 * <ul>
 *   <li>{@code itemId} present → réajuste ce critère (sa {@code valeur} devient
 *       {@code nouvelleValeur}), puis le score total est recalculé.</li>
 *   <li>{@code itemId} absent → surcharge directe du score total de la notation
 *       ({@code score_final = nouvelleValeur}).</li>
 * </ul>
 *
 * {@code motif} est obligatoire : c'est la trace de la réclamation. Sans motif,
 * pas de réajustement (400).
 */
public record ReajustementRequest(
        Long itemId,

        @NotNull(message = "nouvelleValeur est obligatoire")
        Float nouvelleValeur,

        @NotBlank(message = "motif est obligatoire (raison de la réclamation)")
        String motif
) {
}
