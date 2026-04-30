package tn.epos.exam_service.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;
@Data
public class GrilleRequest {
    @NotBlank(message = "Le nom de la grille est obligatoire")
    @Size(max = 150)
    private String nom;

    @DecimalMin(value = "1.0")
    @DecimalMax(value = "100.0")
    private Double noteMax = 20.0;

    @Size(max = 300)
    private String description;

    /**
     * Items optionnels à créer directement avec la grille.
     * Pratique pour initialiser une grille complète en une seule requête.
     */
    @Valid
    private List<ItemRequest> items = new ArrayList<>();
}
