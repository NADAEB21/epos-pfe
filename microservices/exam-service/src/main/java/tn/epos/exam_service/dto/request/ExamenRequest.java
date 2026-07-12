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

    // Tampon de transition inter-créneau, minutes (ADR-0012). 0 = back-to-back.
    @Min(value = 0, message = "Le temps de battement ne peut pas être négatif")
    @Max(value = 60, message = "Le temps de battement ne peut pas dépasser 60 minutes")
    private Integer tempsBattementMin = 0;

    // Délai d'avertissement avant le prochain passage, secondes (ADR-0012).
    // 0 = avertissements désactivés.
    @Min(value = 0, message = "Le délai d'avertissement ne peut pas être négatif")
    @Max(value = 600, message = "Le délai d'avertissement ne peut pas dépasser 600 secondes")
    private Integer avertissementLeadSec = 0;

    @Size(max = 500)
    private String description;
}
