package tn.epos.scoring_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.common.exception.BusinessException;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.entities.ExamGrilleSnapshot;
import tn.epos.scoring_service.entities.ExamItemSnapshot;
import tn.epos.scoring_service.entities.ExamStationSnapshot;
import tn.epos.scoring_service.repositories.ExamItemSnapshotRepository;
import tn.epos.scoring_service.repositories.ExamStationSnapshotRepository;
import tn.epos.scoring_service.repositories.ExamGrilleSnapshotRepository;

import java.util.List;
import java.util.Map;

/**
 * <b>ADR-0015</b> — écriture du snapshot de définition, isolée dans son propre bean.
 *
 * <p><b>Pourquoi un bean séparé et non deux méthodes de
 * {@link ExamDefinitionSnapshotService}.</b> {@code REQUIRES_NEW} est appliqué par le <i>proxy</i>
 * Spring. Un appel {@code this.materialise(...)} depuis le même bean ne traverse pas le proxy :
 * l'annotation devient <b>inerte</b> et la matérialisation rejoint la transaction appelante. Deux
 * garanties de l'ADR tombaient alors silencieusement :
 * <ol>
 *   <li>le rattrapage {@code catch (DataIntegrityViolationException)} ne peut pas fonctionner —
 *       l'INSERT en échec marque la transaction courante {@code rollback-only}, donc la relecture
 *       s'exécute dans une transaction condamnée et l'opération de notation échoue au commit ;</li>
 *   <li>« le snapshot est un fait durable » — il serait annulé avec la transaction métier.</li>
 * </ol>
 *
 * <p>Le passage par un bean distinct rend la nouvelle transaction structurellement vraie, plutôt
 * que dépendante d'une auto-injection facile à supprimer par mégarde lors d'un refactoring.
 *
 * <p>⚠️ <b>Ne jamais figer une valeur de repli.</b> Ce qui est écrit ici est définitif : en cas
 * d'échec amont, on n'écrit rien et on échoue fort (variantes {@code *Strict} du client).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExamDefinitionMaterialiser {

    private final ExamServiceClient examServiceClient;
    private final ExamStationSnapshotRepository stationSnapshotRepository;
    private final ExamItemSnapshotRepository itemSnapshotRepository;
    private final ExamGrilleSnapshotRepository grilleSnapshotRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExamStationSnapshot materialiseStation(Long examenId, Long stationId) {
        String nom = examServiceClient.getStationNomStrict(stationId);

        ExamStationSnapshot snapshot = ExamStationSnapshot.builder()
                .examenId(examenId)
                .stationId(stationId)
                .nom(nom)
                .build();
        try {
            ExamStationSnapshot saved = stationSnapshotRepository.save(snapshot);
            log.info("ADR-0015 : station {} figée « {} » (examen {})", stationId, nom, examenId);
            return saved;
        } catch (DataIntegrityViolationException race) {
            // Deux requêtes concurrentes ont matérialisé la même station : la
            // contrainte UNIQUE a tranché. Le perdant relit le gagnant — les deux
            // valeurs sont identiques (la définition est immuable).
            return stationSnapshotRepository.findByStationId(stationId)
                    .orElseThrow(() -> race);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ExamItemSnapshot> materialiseItems(Long examenId, Long grilleId) {
        Map<Long, ExamServiceClient.ItemInfo> infos = examServiceClient.getItemInfosForGrilleStrict(grilleId);
        if (infos.isEmpty()) {
            // Une grille sans critère notable n'est pas gradable : échouer fort
            // plutôt que figer un ensemble vide, qui rendrait ensuite TOUT item
            // « inconnu » de façon permanente.
            throw new BusinessException(
                    "La grille " + grilleId + " ne déclare aucun critère notable — "
                            + "snapshot non figé (ADR-0015).");
        }

        List<ExamItemSnapshot> rows = infos.values().stream()
                .map(i -> ExamItemSnapshot.builder()
                        .examenId(examenId)
                        .grilleId(grilleId)
                        .itemId(i.id())
                        .type(i.type())
                        .ponderation(i.ponderation())
                        .build())
                .toList();
        try {
            List<ExamItemSnapshot> saved = itemSnapshotRepository.saveAll(rows);
            log.info("ADR-0015 : grille {} figée — {} critère(s) notable(s) (examen {})",
                    grilleId, saved.size(), examenId);
            return saved;
        } catch (DataIntegrityViolationException race) {
            List<ExamItemSnapshot> existing = itemSnapshotRepository.findByGrilleId(grilleId);
            if (existing.isEmpty()) throw race;
            return existing;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExamGrilleSnapshot materialiseGrille(Long examenId, Long stationId) {
        JsonNode data = examServiceClient.getGrilleStrict(stationId);

        // Grille sans items (cas toléré côté mobile, cf. GrilleModel.fromJson) :
        // on stocke un tableau vide explicite, jamais "" ou "null" — sinon la
        // relecture (ObjectMapper.readTree) plante au lieu d'afficher une grille
        // simplement vide.
        JsonNode itemsNode = data.path("items");
        String itemsJson = (itemsNode.isMissingNode() || itemsNode.isNull())
                ? "[]" : itemsNode.toString();

        ExamGrilleSnapshot snapshot = ExamGrilleSnapshot.builder()
                .examenId(examenId)
                .stationId(stationId)
                .grilleId(data.path("id").asLong())
                .nom(data.path("nom").asText(""))
                .noteMax(data.path("noteMax").asDouble(20.0))
                .itemsJson(itemsJson)
                .build();
        try {
            ExamGrilleSnapshot saved = grilleSnapshotRepository.save(snapshot);
            log.info("ADR-0015 : grille de la station {} figée (examen {})", stationId, examenId);
            return saved;
        } catch (DataIntegrityViolationException race) {
            return grilleSnapshotRepository.findByStationId(stationId).orElseThrow(() -> race);
        }
    }
}
