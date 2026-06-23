package tn.epos.scoring_service.dto;

/**
 * Per-row outcome of a bulk import, echoed back so the frontend can render a
 * line-by-line result table. {@code statut} is one of:
 * <ul>
 *   <li>{@code CREATED} — a new student was created in the directory AND enrolled</li>
 *   <li>{@code ENROLLED} — an existing student (matched by numero_inscription) was newly enrolled</li>
 *   <li>{@code ALREADY_ENROLLED} — the student was already on this exam's roster (skipped)</li>
 *   <li>{@code ERROR} — the row was rejected ({@code message} explains why)</li>
 * </ul>
 * {@code ligne} is the 1-based position in the uploaded file, used purely for display.
 */
public record ImportRowResult(
    int ligne,
    String numero_inscription,
    String nom,
    String prenom,
    String statut,
    String message
) {
    public static ImportRowResult of(int ligne, ImportEtudiantRequest r, String statut, String message) {
        return new ImportRowResult(
            ligne,
            r != null ? r.numero_inscription() : null,
            r != null ? r.nom() : null,
            r != null ? r.prenom() : null,
            statut,
            message
        );
    }
}
