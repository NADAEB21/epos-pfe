package tn.epos.scoring_service.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class Rotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long evaluateurId; // clé étrangère vers un utilisateur/examinateur
    private Long stationId;    // FK logique vers exam_db.stations (cross-service, pas de FK SQL)
    private Integer ordrePassage;
    private LocalDateTime debutCreneau;

    // #209 — début RÉEL du passage : l'instant où l'ÉVALUATEUR a ouvert ce groupe
    // (« Poursuivre la notation » / « Groupe suivant »), écrit UNE FOIS. C'est l'ancre du
    // compte à rebours PLANCHER — `debutCreneau` reste le PLAN affiché, il ne chronomètre
    // plus rien (constaté : 12:51 restants sur une station de 2 min). Jamais de statut ni
    // d'action dérivés de ce champ. @Column explicite : précédent `ouvertA` → `ouverta`
    // (la stratégie de nommage n'examine jamais la dernière lettre).
    @Column(name = "debut_reel")
    private LocalDateTime debutReel;

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