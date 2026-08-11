package tn.epos.exam_service.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Entity
@Table(name = "grilles_evaluation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GrilleEvaluation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Le nom de la grille est obligatoire")
    @Size(max = 150)
    @Column(nullable = false, length = 150)
    private String nom;


    @Min(value = 1)
    @Max(value = 100)
    @Column(name = "note_max", nullable = false)
    @Builder.Default
    private Double noteMax = 20.0;

    @Column(length = 300)
    private String description;

    // relations
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id", nullable = false, unique = true)
    private Station station;


    @OneToMany(mappedBy = "grille", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordre ASC")
    @Builder.Default
    private List<ItemEvaluation> items = new ArrayList<>();


    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // methodes
    public boolean isPonderationValide() {
        // 1) somme des items de premier niveau == noteMax
        if (Math.abs(getSommePonderations() - noteMax) >= 0.001) {
            return false;
        }
        // 2) #160 — chaque critère décomposé en sous-critères doit répartir la
        //    TOTALITÉ de sa pondération : sinon les feuilles (seules notées côté
        //    scoring) exposent moins de points que noteMax → note maximale
        //    silencieusement inatteignable. Contrôle indicatif (comme le reste de
        //    ce flag), surfacé dans l'éditeur de grille.
        return items.stream()
                .filter(i -> i.getParent() == null)
                .allMatch(ItemEvaluation::isPonderationEnfantsValide);
    }

    /**
     * #276 — la note maximale qu'un étudiant SANS-FAUTE peut réellement obtenir.
     *
     * <p>À ne pas confondre avec {@link #getSommePonderations()}, qui additionne les
     * budgets déclarés. Les deux peuvent différer sans qu'aucun contrôle existant ne
     * le voie : reproduit en direct sur la grille 76 — budgets 10 + 10 = 20 =
     * {@code noteMax}, donc {@code ponderationValide = true}, et pourtant maximum
     * atteignable = 10, parce que les deux critères sont notés sur 5.
     *
     * <p>C'est cette valeur-là qui compte pour l'étudiant, et c'est elle que le
     * lancement doit comparer à {@code noteMax}.
     */
    public double getMaxAtteignable() {
        return items.stream()
                .filter(i -> i.getParent() == null)
                .mapToDouble(ItemEvaluation::getMaxAtteignable)
                .sum();
    }

    /** #276 — true quand un sans-faute peut atteindre {@code noteMax}. */
    public boolean isNoteMaxAtteignable() {
        return Math.abs(getMaxAtteignable() - noteMax) < 0.001;
    }

    public Double getSommePonderations() {
        return items.stream()
                .filter(i -> i.getParent() == null)   // ← seuls les items de premier niveau comptent pour noteMax
                .mapToDouble(ItemEvaluation::getPonderation)
                .sum();
    }

    public void addItem(ItemEvaluation item) {
        long topLevel = items.stream().filter(i -> i.getParent() == null).count();
        item.setOrdre((int) topLevel + 1);
        items.add(item); // seuls les items de premier niveau vont dans la collection plate
        assignerGrilleRecursivement(item);
    }

    private void assignerGrilleRecursivement(ItemEvaluation item) {
        item.setGrille(this);
        item.getChildren().forEach(this::assignerGrilleRecursivement);
    }

    private void reordonnerItems() {
        List<ItemEvaluation> topLevel = items.stream()
                .filter(i -> i.getParent() == null)
                .collect(Collectors.toList());
        for (int i = 0; i < topLevel.size(); i++) {
            topLevel.get(i).setOrdre(i + 1);
        }
    }

    public void removeItem(ItemEvaluation item) {
        items.remove(item);
        item.setGrille(null);
        reordonnerItems();
    }
}
