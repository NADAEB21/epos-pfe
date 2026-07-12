package tn.epos.scoring_service.dto.dashboard;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Corps de la requête POST /api/evaluateur/notations/saisir.
 *
 * Le Flutter envoie ces données à chaque saisie (critère binaire ou numérique).
 * Le service retrouve automatiquement le RotationAssignment et le NotationItem
 * correspondants, sans que le front n'ait besoin de connaître ces IDs internes.
 *
 * Décalage G corrigé : le Flutter envoie (etudiantId + stationId + itemId + valeur)
 * au lieu de devoir construire lui-même la chaîne Notation→RotationAssignment→Participation.
 *
 * JSON attendu :
 * {
 *   "etudiantId" : 101,
 *   "stationId"  : 3,
 *   "grilleId"   : 1,
 *   "itemId"     : 7,
 *   "valeur"     : 3.0
 * }
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SaisirNotationRequest {

    @NotNull(message = "etudiantId est obligatoire")
    private Long  etudiantId;

    @NotNull(message = "stationId est obligatoire")
    private Long  stationId;

    @NotNull(message = "grilleId est obligatoire")
    private Long  grilleId;

    @NotNull(message = "itemId est obligatoire")
    private Long  itemId;

    @NotNull(message = "valeur est obligatoire")
    private Float valeur;
}