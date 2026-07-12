package tn.epos.scoring_service.entities;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Table(name = "etudiants")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class Etudiant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String numero_inscription;
    private String nom;
    private String prenom;


    @OneToMany(mappedBy = "etudiant")
    private List<ExamenParticipation> participations;
}
