package tn.epos.scoring_service.entities;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class Lot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long examenId;
    private Long evaluateurId;

    private Integer numeroLot;
    private Integer tailleLot;

    @Enumerated(EnumType.STRING)
    private LotStatus statut;

    @OneToMany(mappedBy = "lot")
    private List<ExamenParticipation> participations;

    @OneToMany(mappedBy = "lot")
    private List<StudentGroup> groups;
}