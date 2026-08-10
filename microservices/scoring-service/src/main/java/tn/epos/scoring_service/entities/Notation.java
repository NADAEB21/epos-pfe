package tn.epos.scoring_service.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "notations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class Notation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Float score_final;

    private LocalDateTime timestamp;

    private Integer temps_additionnel;

    // FK logiques vers exam_db (cross-service, pas de FK SQL). Permettent
    // les agrégations BI par station / grille sans 3-table join.
    private Long stationId;
    private Long grilleId;

    private Boolean is_synced;

    private Boolean verouillee;

    // #212 — commentaire d'évaluation PAR (participation, station). Vivait sur
    // ExamenParticipation, ligne partagée entre les N stations : la dernière
    // validation écrasait les autres — et la valeur n'était jamais renvoyée au
    // mobile. Ici, à côté de score_final/verouillee, il est par-station et rejoué.
    @Column(name = "commentaire")
    private String commentaire;

    // #213 — l'auteur RÉEL, enregistré et non déduit. Avant, « qui a noté ? » se
    // lisait sur rotation.evaluateur_id, donc sur le propriétaire de la station :
    // toute écriture par quelqu'un d'autre était attribuée au mauvais évaluateur.
    // Une traçabilité fausse est pire qu'absente — en réclamation, elle accuse.
    // @Column explicite : `saisiPar` se mapperait en `saisipar` (piège ouvertA→ouverta, #208).
    @Column(name = "saisi_par")
    private Long saisiPar;

    @Column(name = "verrouille_par")
    private Long verrouillePar;

    // Lien 1-à-1 avec l'affectation de la rotation
    @OneToOne
    @JoinColumn(name = "assignment_id")
    private RotationAssignment assignment;

    // Relation 1 -> 1..* avec les détails de la notation (NotationItem)
    @OneToMany(mappedBy = "notation", cascade = CascadeType.ALL)
    @JsonIgnore
    private List<NotationItem> items;

    // Initialisation automatique du timestamp à la création
    @PrePersist
    protected void onCreate() {
        this.timestamp = LocalDateTime.now();
}

    /**
     * L'examen dont relève cette notation, ou {@code null} si la chaîne est incomplète — #274.
     *
     * <p>Le chemin fait quatre maillons ({@code assignment → rotation → studentGroup → lot}) et
     * chacun est nullable en base : une notation existe avant son affectation, et
     * {@code NotationService.save} traite déjà explicitement le cas « sans assignment ». Cette
     * méthode vit sur l'entité, et pas dupliquée dans chaque service, pour une raison précise :
     * #274 était né de DEUX chemins vers la même donnée qui répondaient différemment. Une seule
     * définition de « l'examen de cette notation » ne peut pas diverger d'elle-même.
     *
     * <p>Rend {@code null} plutôt que de lever : l'appelant décide. Les gardes d'autorisation
     * échouent FERMÉ sur ce {@code null} ({@code MatiereAccessGuard.checkExamenAccess}), tandis
     * qu'un affichage peut légitimement l'ignorer.
     */
    public Long resolveExamenId() {
        if (assignment == null || assignment.getRotation() == null) {
            return null;
        }
        StudentGroup groupe = assignment.getRotation().getStudentGroup();
        if (groupe == null || groupe.getLot() == null) {
            return null;
        }
        return groupe.getLot().getExamenId();
    }
}
