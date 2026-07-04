package tn.epos.scoring_service.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Audit trail of a single <em>réajustement</em> applied to a (typically locked)
 * {@link Notation} through the ADR-0013 Part 2 channel — the ONE sanctioned way
 * a {@code RESPONSABLE_MATIERE} / {@code SUPER_ADMIN} may change a locked score
 * (student réclamation). Each row records who changed what, from which value to
 * which, and why — so the lock stays a real integrity boundary (#23) instead of
 * a silent back door.
 *
 * <p>This is scoring-service's first audit table. It is stored with plain
 * {@code Long} references (no JPA association to Notation) so it survives the
 * notation's lifecycle independently and can generalise toward the broader
 * audit-trail work (#64). Written <b>synchronously</b> inside the same
 * transaction as the mutation (unlike auth-service's {@code @Async} AuditLog) —
 * the row and the change must commit together or not at all.
 */
@Entity
@Table(name = "notation_adjustments")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Setter
public class NotationAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The adjusted notation (logical FK to notations.id). */
    @Column(name = "notation_id", nullable = false)
    private Long notationId;

    /** The critère adjusted, or {@code null} for a notation-total override. */
    @Column(name = "item_id")
    private Long itemId;

    /** Item value before the change — {@code null} for a total-level adjustment. */
    private Float ancienneValeur;

    /** Item value after the change — {@code null} for a total-level adjustment. */
    private Float nouvelleValeur;

    /** notation.score_final before the change. */
    private Float ancienScore;

    /** notation.score_final after the change. */
    private Float nouveauScore;

    /** Required reason (the réclamation motive). */
    @Column(nullable = false, length = 1000)
    private String motif;

    /** auth userId of the responsable/admin who performed the adjustment. */
    @Column(name = "adjusted_by_user_id", nullable = false)
    private Long adjustedByUserId;

    @Column(name = "adjusted_at", nullable = false)
    private LocalDateTime adjustedAt;

    @PrePersist
    void onCreate() {
        if (this.adjustedAt == null) {
            this.adjustedAt = LocalDateTime.now();
        }
    }
}
