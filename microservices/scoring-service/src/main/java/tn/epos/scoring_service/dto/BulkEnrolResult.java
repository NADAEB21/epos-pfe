package tn.epos.scoring_service.dto;

import java.util.List;

/**
 * Bilan d'une inscription groupée (#186) — même honnêteté que {@link ImportResult} :
 * un compteur par issue PLUS le détail par étudiant, pour qu'un « déjà inscrit »
 * ne soit jamais confondu avec un échec réel et n'interrompe jamais le lot.
 */
public record BulkEnrolResult(
        int total,
        int enrolled,
        int alreadyEnrolled,
        int errors,
        List<BulkEnrolLigne> lignes
) {
    /** {@code statut} : ENROLLED | ALREADY_ENROLLED | ERROR. */
    public record BulkEnrolLigne(Long etudiantId, String nom, String prenom, String statut, String message) {}
}