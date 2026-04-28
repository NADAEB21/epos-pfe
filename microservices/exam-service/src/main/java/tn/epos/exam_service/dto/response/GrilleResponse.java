package tn.epos.exam_service.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class GrilleResponse {
    private Long id;
    private String nom;
    private Double noteMax;
    private String description;
    private Long stationId;

    // Métriques calculées côté service
    private Double sommePonderations;
    private boolean ponderationValide;   // true si sommePonderations == noteMax
    private int nombreItems;

    private List<ItemResponse> items;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
