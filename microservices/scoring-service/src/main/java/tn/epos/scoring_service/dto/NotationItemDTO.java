package tn.epos.scoring_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import tn.epos.scoring_service.entities.NotationItem;

public record NotationItemDTO(
    Long id,
    @JsonProperty("item_id") Long itemId,
    Float valeur,
    String commentaire,
    Long notationId
) {
    @JsonProperty("item_id")
    public static NotationItemDTO fromEntity(NotationItem item) {
        if (item == null) return null;
        return new NotationItemDTO(
            item.getId(),
            item.getItemId(),
            item.getValeur(),
            item.getCommentaire(),
            (item.getNotation() != null) ? item.getNotation().getId() : null
        );
    }
}