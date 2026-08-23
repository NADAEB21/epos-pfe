package tn.epos.scoring_service.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tn.epos.common.exception.BusinessException;
import tn.epos.scoring_service.entities.ExamGrilleSnapshot;

/**
 * Une ligne de GET /api/notations/examen/{examenId}/grilles (#355, écran de
 * délibération) : le barème d'UNE station tel qu'il a réellement servi à noter
 * (exam_grille_snapshot, ADR-0015) — jamais la grille vivante d'exam-service,
 * qui peut avoir bougé depuis. Même contrat que GrilleSnapshotDTO (mobile,
 * #244) : items est réinjecté TEL QUEL depuis itemsJson, jamais reconstruit ;
 * s'y ajoute stationId, la clé de jointure de l'écran Résultats.
 */
public record StationGrilleSnapshotDTO(
        Long stationId, Long grilleId, String nom, Double noteMax, JsonNode items) {

    public static StationGrilleSnapshotDTO fromEntity(ExamGrilleSnapshot snap, ObjectMapper mapper) {
        JsonNode items;
        try {
            items = mapper.readTree(snap.getItemsJson());
        } catch (JsonProcessingException e) {
            // Ne devrait jamais arriver : le matérialiseur ne persiste que du JSON
            // qu'il a lui-même sérialisé. On refuse plutôt que de servir un barème
            // tronqué — l'appelant (le web) replie alors sur la grille vivante.
            throw new BusinessException("Snapshot de grille illisible pour la station "
                    + snap.getStationId() + " — délibération impossible sur ce barème.");
        }
        return new StationGrilleSnapshotDTO(
                snap.getStationId(), snap.getGrilleId(), snap.getNom(), snap.getNoteMax(), items);
    }
}
