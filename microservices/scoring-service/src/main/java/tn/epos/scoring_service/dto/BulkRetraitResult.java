package tn.epos.scoring_service.dto;

import java.util.List;

/**
 * #435 — bilan d'un retrait groupé, ligne par ligne, sur le modèle de {@link BulkEnrolResult} :
 * le responsable doit savoir QUI n'a pas été retiré et POURQUOI, jamais un compteur seul.
 *
 * <p>Statuts : {@code RETIRE} · {@code INTROUVABLE} (déjà retiré, ou id inconnu) ·
 * {@code REFUSE} (la participation n'appartient pas à cet examen) · {@code ERREUR}
 * (contrainte en base — l'étudiant est déjà dans un circuit généré, par exemple).
 */
public record BulkRetraitResult(
        int total,
        int retires,
        int introuvables,
        int erreurs,
        List<BulkRetraitLigne> lignes
) {
    public record BulkRetraitLigne(Long participationId, String nom, String prenom, String statut, String message) {}
}
