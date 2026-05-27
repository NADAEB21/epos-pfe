package tn.epos.exam_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import java.util.List;

@Data
public class GrilleTemplateRequest {

    @NotBlank(message = "Le nom du template est obligatoire")
    private String nom;

    private String description;

    @NotNull @Positive
    private Double noteMax;

    private List<ItemRequest> items;
}
