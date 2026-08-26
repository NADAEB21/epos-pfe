package tn.epos.scoring_service.dto;

import tn.epos.scoring_service.entities.BaremeDeliberation;
import tn.epos.scoring_service.entities.BaremeDeliberationOperation;
import tn.epos.scoring_service.entities.TypeOperationBareme;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Une version d'un barème de délibération avec ses opérations — l'historique
 * VISIBLE d'ADR-0030 D3/D4 (qui, quand, quel motif, quelles opérations). La
 * version la plus haute est la version courante ; une liste {@code operations}
 * vide signifie « retour au barème du lancement ».
 *
 * <p>camelCase, comme les DTO Résultats voisins ({@code ExamenResultDTO}) —
 * même écran, même convention côté web.
 */
public record BaremeDeliberationDTO(
        Long id,
        Long examenId,
        Integer version,
        String motif,
        Long creePar,
        LocalDateTime createdAt,
        List<OperationDTO> operations
) {

    public record OperationDTO(
            TypeOperationBareme type,
            Long cibleItemId,
            Long cibleStationId,
            Double nouvelleEchelle
    ) {
        public static OperationDTO fromEntity(BaremeDeliberationOperation op) {
            return new OperationDTO(
                    op.getType(), op.getCibleItemId(), op.getCibleStationId(),
                    op.getNouvelleEchelle());
        }
    }

    public static BaremeDeliberationDTO fromEntities(
            BaremeDeliberation bareme, List<BaremeDeliberationOperation> operations) {
        return new BaremeDeliberationDTO(
                bareme.getId(),
                bareme.getExamenId(),
                bareme.getVersion(),
                bareme.getMotif(),
                bareme.getCreePar(),
                bareme.getCreatedAt(),
                operations.stream().map(OperationDTO::fromEntity).toList());
    }
}
