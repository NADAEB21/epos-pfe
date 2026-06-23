package tn.epos.scoring_service.dto.dashboard;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Corps de POST /api/evaluateur/etudiants/{id}/stations/{id}/valider.
 * Transporte le statut de présence, le commentaire et l'identifiant
 * de la grille (nécessaire pour créer la Notation si elle n'existe pas encore).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ValiderEtudiantRequest {

    @NotNull(message = "grilleId est obligatoire")
    private Long    grilleId;

    /** true = étudiant absent ; score forcé à 0 et notation verrouillée. */
    private boolean absent;

    /** Remarque ou motif d'absence (optionnel). */
    private String  commentaire;
}
