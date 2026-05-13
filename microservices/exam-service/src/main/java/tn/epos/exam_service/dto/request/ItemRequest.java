package tn.epos.exam_service.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import tn.epos.exam_service.enums.TypeItem;
@Data
public class ItemRequest {
    @NotBlank(message = "Le libellé est obligatoire")
    @Size(max = 300)
    private String libelle;

    @NotNull(message = "Le type est obligatoire (BINAIRE ou NUMERIQUE)")
    private TypeItem type;

    @NotNull(message = "La pondération est obligatoire")
    @DecimalMin(value = "0.5", message = "La pondération minimale est 0.5")
    @DecimalMax(value = "20.0")
    private Double ponderation;

    // Obligatoire si type = NUMERIQUE. Ignoré si type = BINAIRE.
    // Doit être > 0 et ≤ pondération.

    private Double valeurMax;

    @Size(max = 100)
    private String categorie;
}
