package tn.epos.scoring_service.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A student complaint (<em>réclamation</em>) about an exam result, kept in the
 * responsable's register (#136).
 *
 * <p><b>Why this exists on top of the réajustement channel.</b> ADR-0013 Part 2
 * ({@link NotationAdjustment}) already records every <i>change</i> made to a
 * locked score. But a réclamation that is <i>rejected</i> produces no change, so
 * the audit trail alone leaves rejected complaints with no trace. This entity is
 * the complaint logbook: it records the complaint, its disposition
 * ({@link ReclamationStatus}) and — when upheld — a link to the
 * {@code notation_adjustments} row that carried out the correction.
 *
 * <p><b>Filing actor.</b> Students have no login (verified: {@code Etudiant} has
 * no credentials, auth seed has none), so a {@code RESPONSABLE_MATIERE} /
 * {@code SUPER_ADMIN} files the complaint on the student's behalf from a
 * paper/verbal réclamation. {@code createdByUserId} is that responsable, not the
 * student.
 *
 * <p>Stored with plain {@code Long} references (no JPA associations) — same
 * precedent as {@link NotationAdjustment} — so the register survives the
 * lifecycle of the entities it points at. {@code participation_id} is the one
 * exception: it is a real in-DB FK (examen_participations lives in this schema).
 */
@Entity
@Table(name = "reclamations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Setter
public class Reclamation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The exam the complaint concerns (logical FK to exam_db.examens.id). */
    @Column(name = "examen_id", nullable = false)
    private Long examenId;

    /** The contested student participation (FK to examen_participations.id). */
    @Column(name = "participation_id", nullable = false)
    private Long participationId;

    /** The contested notation, when the complaint targets a specific score. */
    @Column(name = "notation_id")
    private Long notationId;

    /** What the student contests — the complaint text. */
    @Column(nullable = false, length = 1000)
    private String objet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReclamationStatus statut;

    /** The responsable's resolution note — set when the complaint is decided. */
    @Column(length = 1000)
    private String reponse;

    /**
     * Link to the {@code notation_adjustments} row that carried out the
     * correction when the complaint was upheld (ACCEPTEE). {@code null} for a
     * pending or rejected complaint. Logical FK — no JPA association.
     */
    @Column(name = "adjustment_id")
    private Long adjustmentId;

    /** auth userId of the responsable/admin who filed the complaint. */
    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** auth userId of the responsable/admin who decided it — null while pending. */
    @Column(name = "resolved_by_user_id")
    private Long resolvedByUserId;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.statut == null) {
            this.statut = ReclamationStatus.EN_ATTENTE;
        }
    }
}
