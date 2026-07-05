package tn.epos.scoring_service.entities;

/**
 * Disposition of a student complaint ({@link Reclamation}) in the responsable's
 * register (#136).
 *
 * <ul>
 *   <li>{@code EN_ATTENTE} — filed, not yet decided (the initial state).</li>
 *   <li>{@code ACCEPTEE} — upheld; the score was (or will be) corrected through
 *       the audited réajustement channel (ADR-0013 Part 2). The resulting
 *       {@code notation_adjustments} row may be linked via
 *       {@code Reclamation.adjustmentId}.</li>
 *   <li>{@code REJETEE} — rejected; the score stands. This is the case the
 *       réajustement audit trail alone could NOT record — a rejection produces
 *       no adjustment row, so without this register it left no trace.</li>
 * </ul>
 */
public enum ReclamationStatus {
    EN_ATTENTE,
    ACCEPTEE,
    REJETEE
}
