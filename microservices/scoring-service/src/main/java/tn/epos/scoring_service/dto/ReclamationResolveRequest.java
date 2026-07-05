package tn.epos.scoring_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tn.epos.scoring_service.entities.ReclamationStatus;

/**
 * Body of {@code PATCH /api/reclamations/{id}/resoudre} — decide a pending
 * complaint (#136).
 *
 * <p>{@code statut} must be a terminal decision (ACCEPTEE or REJETEE — never
 * EN_ATTENTE; the service rejects that). {@code reponse} is the responsable's
 * mandatory written justification of the decision. {@code adjustmentId}
 * optionally links the {@code notation_adjustments} row that carried out the
 * correction when the complaint is upheld — the score change itself is done
 * through the existing réajustement endpoint, keeping the two flows decoupled.
 */
public record ReclamationResolveRequest(

        @NotNull(message = "statut est obligatoire (ACCEPTEE ou REJETEE)")
        ReclamationStatus statut,

        @NotBlank(message = "reponse est obligatoire (justification de la décision)")
        @Size(max = 1000, message = "reponse ne peut dépasser 1000 caractères")
        String reponse,

        Long adjustmentId
) {
}
