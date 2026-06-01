package tn.epos.scoring_service.dto;

import tn.epos.scoring_service.entities.NotationItem;

public record NotationItemDTO(
    Long id,
    Long itemId,
    Float valeur,
    String commentaire,
    Long notationId
) {
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