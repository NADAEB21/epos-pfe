package tn.epos.scoring_service.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Write-once copy of a station's display name, per <b>ADR-0015</b> (issues #240 / #200).
 *
 * <p>Replaces the per-request call to {@code exam-service} that produced the silent
 * {@code "Station <id>"} placeholder whenever that service was unavailable
 * ({@code ExamServiceClient:44}). The name is immutable from {@code EN_COURS} onward —
 * {@code Examen.isGrilleModifiable()} is true only for {@code BROUILLON}/{@code CONFIGURE} —
 * so copying it once is correct, not a compromise.
 *
 * <p><b>Not a cache:</b> it lives in the database (survives restarts/deploys, shared across
 * replicas), it is write-once, and it is never refreshed.
 *
 * <p><b>Failure rule:</b> if the upstream fetch fails, nothing is written and the caller fails
 * loudly. A missing snapshot must never degrade into a placeholder.
 *
 * <p>⚠️ Definition only — no timing, no statut, no derived state (ADR-0015 §4 guardrail).
 *
 * <p>Field names are camelCase with explicit {@code @Column} mappings, deliberately: several
 * older entities in this service use snake_case Java fields, which silently breaks Spring Data
 * derived queries.
 */
@Entity
@Table(name = "exam_station_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamStationSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Logical FK to exam_db.examens — no SQL FK (cross-service, ADR-0006 precedent). */
    @Column(name = "examen_id", nullable = false)
    private Long examenId;

    /** Logical FK to exam_db.stations. Unique: one snapshot row per station. */
    @Column(name = "station_id", nullable = false, unique = true)
    private Long stationId;

    @Column(name = "nom", nullable = false)
    private String nom;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @PrePersist
    protected void onCreate() {
        if (this.capturedAt == null) {
            this.capturedAt = LocalDateTime.now();
        }
    }
}
