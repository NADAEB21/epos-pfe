package tn.epos.exam_service.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import tn.epos.exam_service.enums.TypeItem;

import java.time.LocalDateTime;

@Data
public class ItemResponse {
    private Long id;
    private String libelle;
    private TypeItem type;
    private Double ponderation;
    private Double valeurMax;   // null si BINAIRE
    private Integer ordre;
    private String categorie;
    private Double valeurAttendue;       // clé de réponse (#162)
    private String conditionsAttendues;  // clé de réponse (#162)
    private Long grilleId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
