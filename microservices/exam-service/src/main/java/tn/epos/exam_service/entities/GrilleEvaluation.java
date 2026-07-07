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
        return Math.abs(getSommePonderations() - noteMax) < 0.001;
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
