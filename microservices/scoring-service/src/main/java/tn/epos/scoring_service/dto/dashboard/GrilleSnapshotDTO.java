package tn.epos.scoring_service.dto.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tn.epos.common.exception.BusinessException;
import tn.epos.scoring_service.entities.ExamGrilleSnapshot;

/**
 * Réponse de GET /api/evaluateur/stations/{id}/grille (#244). Forme
 * volontairement identique à GrilleModel.fromJson côté Flutter : id, nom,
 * noteMax, items — items est réinjecté TEL QUEL depuis le snapshot
 * (itemsJson), jamais reconstruit, pour ne jamais diverger de ce que
 * exam-service a réellement renvoyé au moment de la matérialisation.
 */
public record GrilleSnapshotDTO(Long id, String nom, Double noteMax, JsonNode items) {

    public static GrilleSnapshotDTO fromEntity(ExamGrilleSnapshot snap, ObjectMapper mapper) {
        JsonNode items;
        try {
            items = mapper.readTree(snap.getItemsJson());
        } catch (JsonProcessingException e) {
            // Ne devrait jamais arriver : le matérialiseur ne persiste que du
            // JSON qu'il a lui-même sérialisé. Une corruption ici est un
            // signal fort — on refuse plutôt que de servir un items[] vide
            // qui ferait croire à une grille sans critère.
            throw new BusinessException(
                    "Snapshot de grille corrompu pour la station " + snap.getStationId());
        }
        return new GrilleSnapshotDTO(snap.getGrilleId(), snap.getNom(), snap.getNoteMax(), items);
    }
}
