package tn.epos.scoring_service.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * En-tête d'un barème de délibération — ADR-0030 (issue #361, définition finale
 * de #135). Artefact <b>ADDITIF</b> : une couche par-dessus le snapshot gelé
 * d'ADR-0015, jamais une mutation de celui-ci ni des {@code score_final}
 * stockés. L'effet est un recalcul de PRÉSENTATION à la lecture (D4), servi
 * avec les deux dénominateurs.
 *
 * <p><b>Immuable</b> (D3) : une ligne n'est jamais modifiée ni supprimée —
 * corriger, c'est écrire une nouvelle version ({@code max+1}) ; revenir au
 * barème d'origine, c'est une version explicitement VIDE (aucune opération),
 * motivée. La dernière version fait foi ; toute l'histoire reste lisible —
 * même contrat que la chaîne réajustement d'ADR-0013 ({@link NotationAdjustment}),
 * et la seule sémantique compatible avec un procès-verbal (W11).
 *
 * <p>Écrit uniquement par le responsable (garde matière #274 + rôle), examen
 * clos seulement ; l'IA n'a aucun chemin d'écriture vers scoring (ADR-0029 D2).
 * Références en {@code Long} nus (FK logiques cross-service, précédent
 * {@link NotationAdjustment}) ; les opérations filles sont une entité séparée
 * ({@link BaremeDeliberationOperation}) SANS collection JPA côté parent —
 * insert-only, le piège delete+insert 23505 est évité par construction.
 */
@Entity
@Table(name = "bareme_deliberation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BaremeDeliberation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK logique vers exam_db.examens.id. */
    @Column(name = "examen_id", nullable = false)
    private Long examenId;

    /** 1..n par examen — la plus haute est la version courante (D3). */
    @Column(name = "version", nullable = false)
    private Integer version;

    /** Motif OBLIGATOIRE (D1) — l'acte de délibération se justifie toujours. */
    @Column(name = "motif", nullable = false, length = 1000)
    private String motif;

    /** auth userId du responsable/admin auteur (FK logique vers auth_db.users.id). */
    @Column(name = "cree_par", nullable = false)
    private Long creePar;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
