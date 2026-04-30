package tn.epos.scoring_service.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RotationAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Boolean presenceConfirmee;
    private Integer tempsAdditionnel;

    // Plusieurs assignments par rotation
    @ManyToOne
    @JoinColumn(name = "rotation_id")
    private Rotation rotation;

    // Un assignment concerne une participation spécifique
    @ManyToOne
    @JoinColumn(name = "participation_id")
    private ExamenParticipation participation;

    // Relation 1 -> 0..1 vers Notation
    @OneToOne(mappedBy = "assignment", cascade = CascadeType.ALL)
    @JsonIgnore
    private Notation notation;
}