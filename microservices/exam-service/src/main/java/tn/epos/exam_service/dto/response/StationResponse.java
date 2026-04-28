package tn.epos.exam_service.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import tn.epos.exam_service.enums.TypeStation;

import java.time.LocalDateTime;

@Data
public class StationResponse {
    private Long id;
    private String nom;
    private TypeStation type;
    private Integer ordre;
    private String description;
    private Long examenId;
    private boolean hasGrille;

    // Grille incluse si demandée (endpoint détaillé)
    private GrilleResponse grille;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
