package tn.epos.exam_service.entities;

import jakarta.persistence.*;
import lombok.*;
import tn.epos.exam_service.enums.TypeStation;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "stations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Station {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Le nom de la station est obligatoire")
    @Size(max = 150)
    @Column(nullable = false, length = 150)
    private String nom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TypeStation type = TypeStation.PRATIQUE;


    @Min(value = 1)
    @Column(nullable = false)
    @Builder.Default
    private Integer ordre = 1;

    @Column(length = 300)
    private String description;

    @ElementCollection
    @CollectionTable(
            name = "station_evaluateurs",
            joinColumns = @JoinColumn(name = "station_id")
    )
    @Column(name = "evaluateur_id")
    @Builder.Default
    private List<Long> evaluateurIds = new ArrayList<>();

    // relations
    // FetchType.LAZY pour éviter les chargements inutiles
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "examen_id", nullable = false)
    private Examen examen;


    @OneToOne(mappedBy = "station", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private GrilleEvaluation grille;


    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // méthodes
    public void setGrille(GrilleEvaluation grille) {
        this.grille = grille;
        if (grille != null) {
            grille.setStation(this);
        }
    }

    public boolean hasGrille() {
        return this.grille != null;
    }
}
