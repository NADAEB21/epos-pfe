package tn.epos.exam_service.entities;

import jakarta.persistence.*;
import lombok.*;
import tn.epos.exam_service.enums.TypeItem;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
@Entity
@Table(name = "items_evaluation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemEvaluation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    @NotBlank(message = "Le libellé du critère est obligatoire")
    @Size(max = 300)
    @Column(nullable = false, length = 300)
    private String libelle;


    @NotNull(message = "Le type du critère est obligatoire")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TypeItem type;


    @NotNull(message = "La pondération est obligatoire")
    @DecimalMin(value = "0.5", message = "La pondération minimale est 0.5")
    @DecimalMax(value = "20.0", message = "La pondération maximale est 20")
    @Column(nullable = false)
    private Double ponderation;


    @DecimalMin(value = "0.0")
    @Column(name = "valeur_max")
    private Double valeurMax;

    // ordre d'affichage
    @Min(value = 1)
    @Column(nullable = false)
    @Builder.Default
    private Integer ordre = 1;

    // catégorie optionnelle pour regrouper les items
    @Size(max = 100)
    @Column(length = 100)
    private String categorie;

    // Corrigé / clé de réponse (#162) — optionnels, non contraints par le type.
    // valeurAttendue = la valeur numérique attendue (ex: dose correcte) ;
    // conditionsAttendues = description libre des conditions à remplir.
    @DecimalMin(value = "0.0")
    @Column(name = "valeur_attendue")
    private Double valeurAttendue;

    @Size(max = 1000)
    @Column(name = "conditions_attendues", length = 1000)
    private String conditionsAttendues;

    // relations
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "grille_id", nullable = false)
    private GrilleEvaluation grille;


    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // methodes
    // Pour un item NUMERIQUE, valeurMax doit être renseignée et ≤ pondération.
    // Pour un item BINAIRE, valeurMax est ignorée (toujours 0 ou 1).

    public boolean isValide() {
        if (type == TypeItem.NUMERIQUE) {
            return valeurMax != null && valeurMax > 0 && valeurMax <= ponderation;
        }
        return true; // BINAIRE toujours valide si pondération présente
    }
}
