package tn.epos.scoring_service.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "notation_items")
public class NotationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Référence externe vers un item/critère d'évaluation
    private Long item_id;

    private Float valeur;

    private String commentaire;

    // Relation ManyToOne vers Notation (plusieurs items pour une notation)
    @ManyToOne
    @JoinColumn(name = "notation_id")
    private Notation notation;
}