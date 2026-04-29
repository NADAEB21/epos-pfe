package tn.epos.scoring_service.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
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