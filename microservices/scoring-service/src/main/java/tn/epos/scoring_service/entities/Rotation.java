package tn.epos.scoring_service.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Rotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long evaluateurId; // clé étrangère vers un utilisateur/examinateur
    private Integer ordrePassage;
    private LocalDateTime debutCreneau;

    @Enumerated(EnumType.STRING)
    private RotationStatus statut;

    // Relation 1 -> plusieurs assignments
    @OneToMany(mappedBy = "rotation", cascade = CascadeType.ALL)
    @JsonIgnore
    private List<RotationAssignment> assignments;

    // Un groupe auquel appartient la rotation
    @ManyToOne
    @JoinColumn(name = "student_group_id")
    private StudentGroup studentGroup;
}