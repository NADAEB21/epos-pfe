package tn.epos.scoring_service.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    // ADR-0014-B / #252 — instant où le responsable a OUVERT cette vague. Fait observé,
    // pas horaire prévu : c'est la seule ancre honnête pour le chronomètre du Suivi, car
    // `launched_at` ne connaît que le premier lot et `debut_creneau` est un horaire
    // calculé à la génération. NULL = vague jamais ouverte.
    // Écrit uniquement par LotOuvertureService. Mesure d'AFFICHAGE : rien ne doit en être
    // dérivé (ni statut, ni visibilité) sous peine de reconstruire le plafond d'ADR-0014.
    //
    // ⚠️ @Column EXPLICITE, et ce n'est pas du zèle : la stratégie de nommage par défaut de
    // Spring (CamelCaseToUnderscoresNamingStrategy) parcourt `i < length - 1`, donc elle
    // n'examine JAMAIS la dernière lettre. Une majuscule FINALE isolée ne reçoit pas son
    // underscore : `ouvertA` → `ouverta` (alors que `numeroLot` → `numero_lot` fonctionne).
    // Le démarrage échouait sur « Schema-validation: missing column [ouverta] in table [lot] ».
    // Trouvé au lancement réel du conteneur : aucun test à mocks ne pouvait le voir.
    @Column(name = "ouvert_a")
    private LocalDateTime ouvertA;

    @Enumerated(EnumType.STRING)
    private LotStatus statut;

    @OneToMany(mappedBy = "lot")
    private List<ExamenParticipation> participations;

    @OneToMany(mappedBy = "lot")
    private List<StudentGroup> groups;
}