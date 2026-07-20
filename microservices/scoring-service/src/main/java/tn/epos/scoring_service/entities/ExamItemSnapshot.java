package tn.epos.scoring_service.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Write-once copy of a grille's <b>notable (leaf) items</b>, per <b>ADR-0015</b> (issue #240).
 *
 * <p><b>Presence here is the authority on what may be graded.</b> The upstream feed
 * ({@code GET /api/grilles/{id}/items/feuilles}) returns only leaves — <i>"Aplatit l'arbre : ne
 * renvoie que les feuilles"</i> ({@code GrilleService:55}) — so an item absent from this table is,
 * by definition, not notable. That is why no {@code parent_id} is stored: it is neither obtainable
 * from the feed nor needed.
 *
 * <p>This makes the leaf guard <b>local and unconditional</b>. Today
 * {@code saisirNotation:227} reads {@code if (!feuillesValides.isEmpty() && !contains(itemId))} —
 * so an empty remote response <i>skips the guard entirely</i>, letting a parent criterion be graded
 * and then double-counted permanently by {@code recalculerScoreFinal}, which sums every
 * {@code NotationItem} with no leaf filter.
 *
 * <p><b>Corollary that must hold in the scoring path:</b> {@code recalculerScoreFinal} must FAIL on
 * an item missing from this table, never score it raw. Scoring raw is exactly how the outage bug
 * turned a {@code valeur 1 × pondération 5} item into <b>1 point instead of 5</b>, persisted and
 * broadcast with no error.
 *
 * <p>⚠️ Definition only — no timing, no statut, no derived state (ADR-0015 §4 guardrail).
 */
@Entity
@Table(name = "exam_item_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamItemSnapshot {

    /** Item type carrying weight in the score computation. */
    public static final String TYPE_BINAIRE = "BINAIRE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Logical FK to exam_db.examens — enables #183 "dé-lancer" invalidation by exam. */
    @Column(name = "examen_id", nullable = false)
    private Long examenId;

    /** Logical FK to exam_db.grilles_evaluation. */
    @Column(name = "grille_id", nullable = false)
    private Long grilleId;

    /** Logical FK to exam_db.items_evaluation. Unique: one snapshot row per item. */
    @Column(name = "item_id", nullable = false, unique = true)
    private Long itemId;

    /** {@code BINAIRE} | {@code NUMERIQUE}. */
    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "ponderation", nullable = false)
    private double ponderation;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    /**
     * BINAIRE items score {@code valeur × pondération}; everything else scores the raw
     * {@code valeur}. Centralised here so the two historical copies of
     * {@code recalculerScoreFinal} ({@code EvaluateurDashboardService:489},
     * {@code NotationReajustementService:130}) cannot drift apart again — their javadoc already
     * claims they "MUST stay identical" while they had in fact diverged (one guards a null
     * {@code valeur}, the other NPEs on it).
     */
    public float weigh(Float valeur) {
        float v = valeur != null ? valeur : 0f;
        return TYPE_BINAIRE.equals(this.type) ? v * (float) this.ponderation : v;
    }

    @PrePersist
    protected void onCreate() {
        if (this.capturedAt == null) {
            this.capturedAt = LocalDateTime.now();
        }
    }
}
