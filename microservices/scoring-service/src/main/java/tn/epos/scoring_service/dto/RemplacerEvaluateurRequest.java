package tn.epos.scoring_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * ADR-0017 — remplacer l'évaluateur d'une station en pleine épreuve.
 *
 * <p>Le motif est OBLIGATOIRE : une suppléance en cours d'examen doit pouvoir
 * s'expliquer après coup, exactement comme un réajustement de note (ADR-0013).
 * Un champ facultatif serait laissé vide neuf fois sur dix, et la trace ne
 * vaudrait plus rien.
 */
public record RemplacerEvaluateurRequest(
    @NotNull(message = "Le nouvel évaluateur est obligatoire.")
    Long nouvelEvaluateurId,

    @NotBlank(message = "Le motif est obligatoire : une suppléance doit pouvoir s'expliquer.")
    @Size(max = 500)
    String motif
) {}
