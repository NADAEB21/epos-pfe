package tn.epos.exam_service.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import tn.epos.exam_service.enums.StatutExamen;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ExamenResponse {
    private Long id;
    private String nom;
    private Long matiereId;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dateExamen;

    private Integer dureeStationMin;
    private Integer nbEtudiantsParStation;
    private StatutExamen statut;
    private String description;
    private boolean hasPdfSujet;
    private String pdfSujetNom;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    // Stations incluses dans la réponse détaillée
    private List<StationResponse> stations;
}
