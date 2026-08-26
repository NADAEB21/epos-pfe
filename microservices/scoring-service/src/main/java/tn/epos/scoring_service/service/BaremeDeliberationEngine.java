package tn.epos.scoring_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tn.epos.scoring_service.entities.BaremeDeliberation;
import tn.epos.scoring_service.entities.BaremeDeliberationOperation;
import tn.epos.scoring_service.entities.ExamGrilleSnapshot;
import tn.epos.scoring_service.entities.ExamItemSnapshot;
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.entities.TypeOperationBareme;
import tn.epos.scoring_service.repositories.ExamGrilleSnapshotRepository;
import tn.epos.scoring_service.repositories.ExamItemSnapshotRepository;
import tn.epos.scoring_service.repositories.IBaremeDeliberationOperationRepository;
import tn.epos.scoring_service.repositories.IBaremeDeliberationRepository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Applique la version COURANTE du barème de délibération sur le snapshot intact
 * — le recalcul de PRÉSENTATION d'ADR-0030 D4. Rien n'est écrit : ni le
 * snapshot, ni les {@code score_final} stockés ; tout se calcule à la lecture.
 *
 * <p><b>Arithmétique en DELTA depuis les valeurs STOCKÉES</b>, pas une
 * re-somme complète des items : le numérateur délibéré part de
 * {@code score_final} (qui peut porter un réajustement ADR-0013 au TOTAL, sans
 * trace item — une re-somme l'écraserait) et ne retranche/ré-échelonne que les
 * critères CIBLÉS ; le dénominateur délibéré part de {@code note_max} (le barème
 * déclaré, servi comme dénominateur original) et applique les mêmes deltas. Un
 * barème VIDE (retour à l'origine, D3) rend donc exactement les valeurs
 * originales, par construction.
 *
 * <p><b>Chemin de lecture 100 % local</b> : tout vient de scoring_db (snapshots
 * + barème + items de notation) — aucune dépendance à exam-service, la
 * délibération vit même exam-service éteint (posture #355). Les cibles dont le
 * maximum serait inconnu (NUMERIQUE sans {@code valeurMax} au snapshot) sont
 * refusées à la CRÉATION par {@link BaremeDeliberationService} — ici on peut
 * donc supposer les maxima résolubles, et on dégrade en loggant si un snapshot
 * a disparu entre-temps (dé-lancement #183).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BaremeDeliberationEngine {

    private final IBaremeDeliberationRepository baremeRepository;
    private final IBaremeDeliberationOperationRepository operationRepository;
    private final ExamItemSnapshotRepository itemSnapshotRepository;
    private final ExamGrilleSnapshotRepository grilleSnapshotRepository;
    private final ObjectMapper objectMapper;

    /** Une opération sur un critère, résolue : le snapshot de l'item + son maximum. */
    public record OperationItem(
            ExamItemSnapshot item, double max, TypeOperationBareme type, Double nouvelleEchelle) {
    }

    /**
     * La version courante APPLIQUÉE : tout ce que le calcul de lecture demande,
     * résolu une fois par requête (jamais par notation).
     */
    public record BaremeApplique(
            BaremeDeliberation version,
            List<BaremeDeliberationOperation> operations,
            Set<Long> stationsExclues,
            /* stationId → nouvelle échelle (REPONDERER station) */
            Map<Long, Double> echelleParStation,
            /* itemId ciblé → opération résolue */
            Map<Long, OperationItem> operationsParItem,
            /* grilleId → stationId (jointure snapshot, exam_item_snapshot n'a pas de station) */
            Map<Long, Long> stationParGrille,
            /* stationId → note_max déclarée (dénominateur ORIGINAL) */
            Map<Long, Double> maxOriginalParStation,
            /* stationId → max délibéré ; absente = station exclue */
            Map<Long, Double> maxDelibereParStation) {
    }

    /**
     * Charge et résout la version courante d'un examen — {@code empty} si aucun
     * barème de délibération n'existe (les champs délibérés restent {@code null}
     * dans la réponse : « pas de barème » n'est pas « barème vide »).
     */
    public Optional<BaremeApplique> chargerCourant(Long examenId) {
        Optional<BaremeDeliberation> courant =
                baremeRepository.findTopByExamenIdOrderByVersionDesc(examenId);
        if (courant.isEmpty()) {
            return Optional.empty();
        }
        BaremeDeliberation version = courant.get();
        List<BaremeDeliberationOperation> ops = operationRepository.findByBaremeId(version.getId());

        List<ExamGrilleSnapshot> grilles = grilleSnapshotRepository.findByExamenId(examenId);
        Map<Long, Long> stationParGrille = new HashMap<>();
        Map<Long, Double> maxOriginal = new HashMap<>();
        for (ExamGrilleSnapshot g : grilles) {
            stationParGrille.put(g.getGrilleId(), g.getStationId());
            maxOriginal.put(g.getStationId(), g.getNoteMax());
        }
        Map<Long, Double> valeurMaxParItem = valeurMaxParItem(grilles);
        Map<Long, ExamItemSnapshot> itemsParId = new HashMap<>();
        for (ExamItemSnapshot s : itemSnapshotRepository.findByExamenId(examenId)) {
            itemsParId.put(s.getItemId(), s);
        }

        Set<Long> stationsExclues = new HashSet<>();
        Map<Long, Double> echelleParStation = new HashMap<>();
        Map<Long, OperationItem> operationsParItem = new HashMap<>();
        for (BaremeDeliberationOperation op : ops) {
            if (op.getType() == TypeOperationBareme.EXCLURE_STATION) {
                stationsExclues.add(op.getCibleStationId());
            } else if (op.getCibleStationId() != null) {
                echelleParStation.put(op.getCibleStationId(), op.getNouvelleEchelle());
            } else {
                ExamItemSnapshot item = itemsParId.get(op.getCibleItemId());
                Double max = maxDeItem(item, valeurMaxParItem);
                if (item == null || max == null) {
                    // Validé à la création — n'arrive que si le snapshot a été
                    // invalidé depuis (#183). On refuse de calculer un délibéré
                    // faux : l'opération est ignorée ET criée dans le log.
                    log.error("Barème délibération v{} examen {} : cible critère {} "
                                    + "irrésoluble dans le snapshot — opération ignorée au calcul.",
                            version.getVersion(), examenId, op.getCibleItemId());
                    continue;
                }
                operationsParItem.put(op.getCibleItemId(),
                        new OperationItem(item, max, op.getType(), op.getNouvelleEchelle()));
            }
        }

        // Dénominateur délibéré par station : delta depuis note_max.
        Map<Long, Double> maxDelibere = new HashMap<>();
        for (Map.Entry<Long, Double> e : maxOriginal.entrySet()) {
            Long stationId = e.getKey();
            if (stationsExclues.contains(stationId)) {
                continue; // absente = exclue des deux sommes
            }
            if (echelleParStation.containsKey(stationId)) {
                maxDelibere.put(stationId, echelleParStation.get(stationId));
                continue;
            }
            double max = e.getValue();
            for (OperationItem opItem : operationsParItem.values()) {
                Long itemStation = stationParGrille.get(opItem.item().getGrilleId());
                if (!stationId.equals(itemStation)) continue;
                if (opItem.type() == TypeOperationBareme.EXCLURE_CRITERE) {
                    max -= opItem.max();
                } else { // REPONDERER critère
                    max += opItem.nouvelleEchelle() - opItem.max();
                }
            }
            maxDelibere.put(stationId, Math.max(0d, max));
        }

        return Optional.of(new BaremeApplique(version, ops, stationsExclues, echelleParStation,
                operationsParItem, stationParGrille, maxOriginal, maxDelibere));
    }

    /**
     * Score délibéré d'UNE notation (une station d'un étudiant), en delta depuis
     * {@code score_final}. {@code null} si la station est exclue (l'appelant la
     * sort des totaux) ou si le score stocké est {@code null}.
     *
     * @param valeursParItem les valeurs saisies de CETTE notation, par itemId —
     *                       un critère ciblé jamais saisi contribue 0 aux deux
     *                       barèmes, le delta est donc nul pour lui.
     */
    public Float scoreDelibere(BaremeApplique bareme, Notation notation,
                               Map<Long, Float> valeursParItem) {
        Long stationId = notation.getStationId();
        if (stationId != null && bareme.stationsExclues().contains(stationId)) {
            return null;
        }
        if (notation.getScore_final() == null) {
            return null;
        }
        double score = notation.getScore_final();

        for (OperationItem opItem : bareme.operationsParItem().values()) {
            Long itemStation = bareme.stationParGrille().get(opItem.item().getGrilleId());
            if (itemStation == null || !itemStation.equals(stationId)) continue;
            Float valeur = valeursParItem.get(opItem.item().getItemId());
            double contribution = opItem.item().weigh(valeur);
            if (opItem.type() == TypeOperationBareme.EXCLURE_CRITERE) {
                score -= contribution;
            } else { // REPONDERER critère : ré-échelonnage proportionnel (ADR-0021 D8)
                score += rescale(opItem, valeur) - contribution;
            }
        }

        Double echelle = bareme.echelleParStation().get(stationId);
        if (echelle != null) {
            Double base = bareme.maxOriginalParStation().get(stationId);
            score = (base != null && base > 0) ? score / base * echelle : 0d;
        }
        return (float) Math.max(0d, score);
    }

    /** Performance de l'item re-mise à l'échelle vers {@code nouvelleEchelle}. */
    private double rescale(OperationItem opItem, Float valeur) {
        double v = valeur != null ? valeur : 0d;
        if (ExamItemSnapshot.TYPE_BINAIRE.equals(opItem.item().getType())) {
            return v * opItem.nouvelleEchelle(); // v ∈ {0,1}
        }
        return opItem.max() > 0 ? v / opItem.max() * opItem.nouvelleEchelle() : 0d;
    }

    /**
     * Le maximum d'un critère du snapshot : BINAIRE → pondération ;
     * NUMERIQUE → {@code valeurMax} lu de {@code items_json} ({@code null} si le
     * snapshot ne le porte pas — cible alors REFUSÉE à la création).
     * {@code exam_item_snapshot} seul ne suffit pas : il ne stocke ni
     * {@code valeur_max} ni {@code libelle} (ADR-0015, définition minimale).
     */
    public Double maxDeItem(ExamItemSnapshot item, Map<Long, Double> valeurMaxParItem) {
        if (item == null) return null;
        if (ExamItemSnapshot.TYPE_BINAIRE.equals(item.getType())) {
            return item.getPonderation();
        }
        return valeurMaxParItem.get(item.getItemId());
    }

    /**
     * {@code itemId → valeurMax} depuis les {@code items_json} des grilles
     * snapshotées (arbre aplati, feuilles ET parents — seuls les ids validés
     * contre {@code exam_item_snapshot}, feuilles par construction, sont lus).
     * Une ligne illisible est sautée en loggant (posture #355 : une station
     * corrompue n'éteint pas la délibération).
     */
    public Map<Long, Double> valeurMaxParItem(List<ExamGrilleSnapshot> grilles) {
        Map<Long, Double> out = new HashMap<>();
        for (ExamGrilleSnapshot g : grilles) {
            try {
                collect(objectMapper.readTree(g.getItemsJson()), out);
            } catch (Exception e) {
                log.error("items_json illisible (station {}) — valeurMax indisponibles : {}",
                        g.getStationId(), e.getMessage());
            }
        }
        return out;
    }

    private void collect(JsonNode node, Map<Long, Double> out) {
        // MissingNode.path(...) rend LE singleton MissingNode — sans cette garde,
        // collect(missing) se rappelle lui-même à l'infini (StackOverflowError,
        // attrapé par les tests de validation).
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isArray()) {
            for (JsonNode child : node) collect(child, out);
            return;
        }
        if (node.path("id").isNumber() && node.path("valeurMax").isNumber()) {
            out.put(node.path("id").asLong(), node.path("valeurMax").asDouble());
        }
        collect(node.path("sousCriteres"), out);
    }
}
