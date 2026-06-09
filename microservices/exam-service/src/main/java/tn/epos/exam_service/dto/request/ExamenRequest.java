package tn.epos.exam_service.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
@Data
public class ExamenRequest {
    @NotBlank(message = "Le nom est obligatoire")
    @Size(max = 150, message = "Le nom ne doit pas dépasser 150 caractères")
    private String nom;

    @NotNull(message = "La matière est obligatoire")
    private Long matiereId;

    @NotNull(message = "La date de l'examen est obligatoire")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dateExamen;

    // Heure de début du circuit. Optionnelle — défaut 09:00 si absente.
    @JsonFormat(pattern = "HH:mm")
    private LocalTime heureDebut = LocalTime.of(9, 0);

    @Min(value = 1, message = "La durée doit être au moins 1 minute")
    @Max(value = 180)
    private Integer dureeStationMin = 15;

    @Min(value = 1)
    @Max(value = 10)
    private Integer nbEtudiantsParStation = 4;

    @Size(max = 500)
    private String description;
}
