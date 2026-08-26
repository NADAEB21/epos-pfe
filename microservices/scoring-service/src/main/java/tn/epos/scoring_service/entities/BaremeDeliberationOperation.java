package tn.epos.scoring_service.entities;

import jakarta.persistence.*;
import lombok.*;

/**
 * Une opération d'un barème de délibération (ligne fille de
 * {@link BaremeDeliberation}) — ADR-0030 D2. Type fermé
 * ({@link TypeOperationBareme}), cible = id du SNAPSHOT
 * ({@code exam_item_snapshot.item_id} ou {@code exam_grille_snapshot.station_id}),
 * jamais la grille vivante : le barème délibéré se définit par rapport à ce qui
 * a réellement servi à noter.
 *
 * <p>Volontairement SANS association JPA vers le parent (plain {@code Long}
 * {@code baremeId}, FK réelle en base) : les versions sont immuables et les
 * opérations insert-only — pas de collection gérée, donc pas de piège
 * delete+insert (23505) possible sur ce chemin.
 */
@Entity
@Table(name = "bareme_deliberation_operation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BaremeDeliberationOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK (réelle en base, ON DELETE CASCADE) vers bareme_deliberation.id. */
    @Column(name = "bareme_id", nullable = false)
    private Long baremeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private TypeOperationBareme type;

    /** Cible critère (EXCLURE_CRITERE, REPONDERER item) — exam_item_snapshot.item_id. */
    @Column(name = "cible_item_id")
    private Long cibleItemId;

    /** Cible station (EXCLURE_STATION, REPONDERER station) — exam_grille_snapshot.station_id. */
    @Column(name = "cible_station_id")
    private Long cibleStationId;

    /** REPONDERER seulement : la nouvelle échelle (> 0). NULL pour les exclusions. */
    @Column(name = "nouvelle_echelle")
    private Double nouvelleEchelle;
}
