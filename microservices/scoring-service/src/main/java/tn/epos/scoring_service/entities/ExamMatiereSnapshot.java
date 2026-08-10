package tn.epos.scoring_service.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Matière d'un examen, figée localement — <b>ADR-0015</b>, #274.
 *
 * <p><b>Ce n'est pas un cache, c'est l'autorité locale d'autorisation.</b> scoring-service borne
 * un {@code RESPONSABLE_MATIERE} à SES matières ; il lui faut donc répondre à « à quelle matière
 * appartient l'examen N ? » <b>sans appeler exam-service à chaque écriture</b> — un appel par
 * écriture ferait tomber l'autorisation avec exam-service, ce qu'ADR-0015 interdit précisément.
 *
 * <p>Écrit une seule fois, jamais rafraîchi : {@code Examen.matiere_id} est immuable une fois
 * l'examen lancé, donc rien ne se périme. Même nature que les pondérations de
 * {@link ExamItemSnapshot} — un attribut de DÉFINITION, pas un état vivant.
 *
 * <p>⚠️ Champs camelCase avec {@code @Column} <b>explicites</b>, et ce n'est pas du zèle : la
 * stratégie de nommage par défaut de Spring parcourt {@code i < length - 1} et n'examine donc
 * jamais la dernière lettre — une majuscule finale isolée ne reçoit pas son underscore
 * ({@code ouvertA → ouverta}, piège payé sur {@code Lot}). Rendre le mapping explicite le met
 * hors de portée de cette classe de panne, qu'aucun test à mocks ne peut voir.
 */
@Entity
@Table(name = "exam_matiere_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamMatiereSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK logique vers exam_db.examens — pas de FK SQL (cross-service, précédent ADR-0006). */
    @Column(name = "examen_id", nullable = false, unique = true)
    private Long examenId;

    /** FK logique vers auth_db.matieres — la matière propriétaire de l'examen. */
    @Column(name = "matiere_id", nullable = false)
    private Long matiereId;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @PrePersist
    protected void onCreate() {
        if (this.capturedAt == null) {
            this.capturedAt = LocalDateTime.now();
        }
    }
}
