package tn.epos.scoring_service.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "notations")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Notation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Float score_final;

    private LocalDateTime timestamp;

    private Integer temps_additionnel;

    // FK logiques vers exam_db (cross-service, pas de FK SQL). Permettent
    // les agrégations BI par station / grille sans 3-table join.
    private Long stationId;
    private Long grilleId;

    private Boolean is_synced;

    private Boolean verouillee;

    // Lien 1-à-1 avec l'affectation de la rotation
    @OneToOne
    @JoinColumn(name = "assignment_id")
    private RotationAssignment assignment;

    // Relation 1 -> 1..* avec les détails de la notation (NotationItem)
    @OneToMany(mappedBy = "notation", cascade = CascadeType.ALL)
    @JsonIgnore
    private List<NotationItem> items;

    // Initialisation automatique du timestamp à la création
    @PrePersist
    protected void onCreate() {
        this.timestamp = LocalDateTime.now();
}
}
