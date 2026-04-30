package tn.epos.scoring_service.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Entity
@Table(name = "examen_participations")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExamenParticipation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Représente le <<FK>> examen_id du diagramme
    private Long examen_id;

    private String num_echantillon;
    private Float note;

    private Boolean est_present;

    // Relation avec Etudiant (le 1 du côté Etudiant et 0..* ici)
    @ManyToOne
    @JoinColumn(name = "etudiant_id")
    private Etudiant etudiant;

    @ManyToOne
    @JoinColumn(name = "lot_id")
    private Lot lot;
}
