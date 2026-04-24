package tn.epos.exam_service.dto.request;

import lombok.Data;
import jakarta.validation.constraints.*;
import tn.epos.exam_service.enums.TypeStation;
@Data
public class StationRequest {
    @NotBlank(message = "Le nom de la station est obligatoire")
    @Size(max = 150)
    private String nom;

    @NotNull(message = "Le type de la station est obligatoire (PRATIQUE ou THEORIQUE)")
    private TypeStation type;

    @Size(max = 300)
    private String description;
}
