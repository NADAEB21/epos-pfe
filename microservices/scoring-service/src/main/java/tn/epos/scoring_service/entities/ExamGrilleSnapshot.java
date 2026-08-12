package tn.epos.scoring_service.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
@Entity
@Table(name = "exam_grille_snapshot")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExamGrilleSnapshot {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "examen_id", nullable = false)
    private Long examenId;

    @Column(name = "station_id", nullable = false, unique = true)
    private Long stationId;

    @Column(name = "grille_id", nullable = false)
    private Long grilleId;

    @Column(name = "nom", nullable = false)
    private String nom;

    @Column(name = "note_max", nullable = false)
    private double noteMax;

    /** items[] tel que reçu d'exam-service, avec hiérarchie sousCriteres intacte. */
    @Column(name = "items_json", nullable = false, columnDefinition = "text")
    private String itemsJson;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @PrePersist
    protected void onCreate() {
        if (this.capturedAt == null) this.capturedAt = LocalDateTime.now();
    }
}