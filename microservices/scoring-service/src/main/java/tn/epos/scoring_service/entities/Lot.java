package tn.epos.scoring_service.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
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

    // ADR-0014-A §5 / #147 — jour de passage du lot (multi-jour). NULL = le lot
    // tourne le jour unique de l'examen (exam_db.dateExamen). Renseigné seulement
    // quand le responsable étale une cohorte sur plusieurs jours. PLAN, pas PACE.
    private LocalDate jour;

    @Enumerated(EnumType.STRING)
    private LotStatus statut;

    @OneToMany(mappedBy = "lot")
    private List<ExamenParticipation> participations;

    @OneToMany(mappedBy = "lot")
    private List<StudentGroup> groups;
}