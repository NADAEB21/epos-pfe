package tn.epos.scoring_service.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * ADR-0017 — trace d'une suppléance : qui a remplacé qui, sur quelle station,
 * pourquoi, et combien de groupes ont changé de main.
 *
 * <p>Avant, changer l'évaluateur d'une rotation n'était possible que par
 * l'écriture générique de {@code RotationController} : sans garde, rotation par
 * rotation, sans distinguer le travail fini du travail restant, et sans aucune
 * trace. Remplacer un évaluateur en pleine épreuve est un acte d'organisation
 * lourd — il doit pouvoir être expliqué après coup, d'où le motif obligatoire
 * (même exigence que le réajustement d'une note, ADR-0013).
 */
@Entity
@Table(name = "evaluateur_substitution")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class EvaluateurSubstitution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lot_id", nullable = false)
    private Long lotId;

    /** FK logique vers exam_db (pas de contrainte SQL cross-base). */
    @Column(name = "station_id", nullable = false)
    private Long stationId;

    @Column(name = "ancien_evaluateur", nullable = false)
    private Long ancienEvaluateur;

    @Column(name = "nouvel_evaluateur", nullable = false)
    private Long nouvelEvaluateur;

    /** Combien de groupes non terminés ont changé de main. */
    @Column(name = "rotations_transferees", nullable = false)
    private Integer rotationsTransferees;

    @Column(name = "motif", nullable = false, length = 500)
    private String motif;

    /** Le responsable qui a décidé — jamais l'évaluateur (ADR-0017 §1). */
    @Column(name = "decide_par", nullable = false)
    private Long decidePar;

    @Column(name = "survenu_a", nullable = false)
    private LocalDateTime survenuA;
}
