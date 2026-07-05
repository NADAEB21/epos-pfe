package tn.epos.scoring_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/reclamations} — file a new student complaint (#136).
 *
 * <p>Filed by a RESPONSABLE_MATIERE / SUPER_ADMIN on the student's behalf
 * (students have no login). The complaint always concerns one participation of
 * one exam; {@code notationId} is optional (the contested score may not be
 * materialised as a specific notation, or the complaint is about the result in
 * general).
 */
public record ReclamationRequest(

        @NotNull(message = "examenId est obligatoire")
        Long examenId,

        @NotNull(message = "participationId est obligatoire")
        Long participationId,

        Long notationId,

        @NotBlank(message = "objet est obligatoire (motif de la réclamation)")
        @Size(max = 1000, message = "objet ne peut dépasser 1000 caractères")
        String objet
) {
}
