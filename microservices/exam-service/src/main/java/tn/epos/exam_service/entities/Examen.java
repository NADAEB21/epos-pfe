package tn.epos.exam_service.entities;

import jakarta.persistence.*;
import lombok.*;
import tn.epos.exam_service.enums.StatutExamen;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "examens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Examen {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Le nom de l'examen est obligatoire")
    @Size(max = 150, message = "Le nom ne doit pas dépasser 150 caractères")
    @Column(nullable = false, length = 150)
    private String nom;

    @NotBlank(message = "La matière est obligatoire")
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String matiere;

    @NotNull(message = "La date de l'examen est obligatoire")
    @Column(name = "date_examen", nullable = false)
    private LocalDate dateExamen;


    @Min(value = 1, message = "La durée doit être au moins 1 minute")
    @Max(value = 180, message = "La durée ne peut pas dépasser 180 minutes")
    @Column(name = "duree_station_min", nullable = false)
    @Builder.Default
    private Integer dureeStationMin = 15;


    @Min(value = 1, message = "Au moins 1 étudiant par station")
    @Max(value = 10)
    @Column(name = "nb_etudiants_par_station", nullable = false)
    @Builder.Default
    private Integer nbEtudiantsParStation = 4;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StatutExamen statut = StatutExamen.BROUILLON;

    // chemin vers le fichier PDF (stockage serveur)
    @Column(name = "pdf_sujet_path")
    private String pdfSujetPath;

    // nom du fichier PDF
    @Column(name = "pdf_sujet_nom")
    private String pdfSujetNom;

    @Column(name = "description", length = 500)
    private String description;

    // relations
    // orphanRemoval : retirer une station de la liste la supprime en BDD.
    @OneToMany(mappedBy = "examen", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordre ASC")
    @Builder.Default
    private List<Station> stations = new ArrayList<>();


    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // methodes
    public void addStation(Station station) {
        stations.add(station);
        station.setExamen(this);
    }

    public void removeStation(Station station) {
        stations.remove(station);
        station.setExamen(null);
    }

    public boolean isModifiable() {
        return this.statut == StatutExamen.BROUILLON;
    }


    public boolean isGrilleModifiable() {
        return this.statut == StatutExamen.BROUILLON || this.statut == StatutExamen.CONFIGURE;
    }
}
