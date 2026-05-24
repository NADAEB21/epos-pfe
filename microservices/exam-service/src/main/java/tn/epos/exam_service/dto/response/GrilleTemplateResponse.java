package tn.epos.exam_service.dto.response;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class GrilleTemplateResponse {
    private Long id;
    private String nom;
    private String description;
    private Double noteMax;
    private int nombreItems;
    private Double sommePonderations;
    private LocalDateTime createdAt;
    private List<ItemResponse> items;
}
