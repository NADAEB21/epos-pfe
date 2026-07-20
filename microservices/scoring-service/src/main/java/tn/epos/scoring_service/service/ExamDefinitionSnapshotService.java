package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.common.exception.BusinessException;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.entities.ExamItemSnapshot;
import tn.epos.scoring_service.entities.ExamStationSnapshot;
import tn.epos.scoring_service.repositories.ExamItemSnapshotRepository;
import tn.epos.scoring_service.repositories.ExamStationSnapshotRepository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <b>ADR-0015</b> — materialises the immutable exam definition into {@code scoring_db}, write-once,
 * so grading never depends on {@code exam-service} being reachable.
 *
 * <p><b>Why this exists.</b> scoring-service used to fetch station names and item pondérations over
 * the network on every request. Those values cannot change once an exam is {@code EN_COURS}
 * ({@code Examen.isGrilleModifiable()} is true only for {@code BROUILLON}/{@code CONFIGURE}), so the
 * fetch bought nothing and cost three silent failures during an outage (reproduced 2026-07-20):
 * placeholder station names, BINAIRE items scored raw (1 instead of 5) and persisted, and a
 * fail-open leaf guard admitting parent criteria that then double-count forever.
 *
 * <p><b>This is not a cache.</b> It is in the database (survives restarts/deploys, shared across
 * replicas), write-once (so a grade means what it meant on exam day), and never refreshed — which is
 * correct, because edits are already blocked from {@code EN_COURS} onward. The previous in-memory
 * {@code grilleItemsCache} had none of those properties: lost on every deploy, never invalidated,
 * per-instance, and it silently ignored grille edits forever.
 *
 * <p><b>Failure rule — do not soften this.</b> If the upstream fetch fails, <b>nothing is written
 * and the caller fails loudly</b>. A missing snapshot must never degrade into a placeholder name or
 * an unweighted score. The cost is a narrow window right after launch during which grading cannot
 * start if exam-service is down — but it stops <i>visibly</i> instead of silently recording wrong
 * marks. After the first success, exam-service may stay down indefinitely with no effect.
 *
 * <p>⚠️ <b>No clock authority</b> (ADR-0015 §4). This service stores definition only — names, types,
 * pondérations. No timing, no statut, no derived state. {@code debutCreneau} remains an indicative
 * PLAN (ADR-0014-A) and {@code dureeStationMin} is not stored here: it sizes a countdown (a FLOOR —
 * the student gets their time) and must never retire a session (a CEILING).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExamDefinitionSnapshotService {

    private final ExamStationSnapshotRepository stationSnapshotRepository;
    private final ExamItemSnapshotRepository itemSnapshotRepository;

    /** Consulté UNIQUEMENT par le chemin d'affichage, pour son état de santé. */
    private final ExamServiceClient examServiceClient;

    /**
     * Bean distinct <b>à dessein</b> : {@code REQUIRES_NEW} n'est appliqué que si l'appel traverse
     * le proxy Spring. Un {@code this.materialise(...)} le contournerait et rendrait l'annotation
     * inerte — voir {@link ExamDefinitionMaterialiser} pour les deux garanties que cela cassait.
     */
    private final ExamDefinitionMaterialiser materialiser;

    /**
     * The station's real display name. Materialises on first use.
     *
     * @throws BusinessException if not yet snapshotted and exam-service cannot supply it. Callers
     *                           must surface this — never substitute {@code "Station <id>"}.
     */
    @Transactional
    public String resolveStationNom(Long examenId, Long stationId) {
        return stationSnapshotRepository.findByStationId(stationId)
                .map(ExamStationSnapshot::getNom)
                .orElseGet(() -> materialiser.materialiseStation(examenId, stationId).getNom());
    }

    /**
     * Étiquette explicite substituée au nom d'une station qu'on ne sait pas résoudre.
     *
     * <p>Volontairement <b>ni plausible ni vide</b> : « Station 5 » se lit comme un vrai intitulé
     * (c'est le repli que l'ADR supprime), et {@code null} rendrait un libellé vide côté mobile
     * ({@code json['stationNom'] ?? ''}) — la même classe de fuite de valeur-repli dans l'UI.
     * Ici l'évaluateur voit que l'information manque.
     */
    public static final String NOM_INDISPONIBLE = "Intitulé indisponible";

    /**
     * Nom de station <b>pour l'affichage</b> — dégrade au lieu d'échouer.
     *
     * <p><b>Pourquoi ce jumeau non strict.</b> Écriture et lecture n'ont pas le même enjeu : une
     * note fausse persistée est un dégât permanent (d'où {@link #resolveStationNom}, strict), alors
     * qu'un intitulé est de la métadonnée d'affichage — rien d'irréversible. Faire échouer tout le
     * tableau de bord parce qu'UNE station est irrésolue est disproportionné, et cela contredit la
     * promesse même de l'ADR : les sessions déjà figées ont justement été gelées pour ne plus
     * dépendre d'exam-service.
     *
     * <p>Constaté en live le 2026-07-20 : pendant une panne d'exam-service, le repli-ouvert de
     * #241 fait remonter les sessions d'examens auxquels l'évaluateur est affecté mais qu'il ne
     * joue pas (stations 1/9/26/49 pour eval3) ; jamais figées, elles faisaient échouer le
     * dashboard entier en HTTP 400 — y compris pour les 4 sessions parfaitement figées.
     * On dégrade donc <b>session par session</b>. On ne filtre PAS les sessions parasites :
     * déterminer l'ensemble des sessions vivantes sans exam-service est la question de #241, et
     * en retirer risquerait de recréer l'impasse « aucune session » de #238.
     */
    @Transactional
    public String resolveStationNomPourAffichage(Long examenId, Long stationId) {
        // Chemin d'AFFICHAGE : il consulte l'état de santé d'exam-service au lieu de
        // retenter N fois. Le dashboard fait UN resolve par session ; pendant une panne,
        // chacun payait son délai plein (mesuré : ~3,07 s × 8 sessions non figées, d'où
        // un dashboard à 31–61 s alors que le client mobile abandonne à 20 s).
        // La station DÉJÀ figée n'atteint jamais cette branche : elle sort du snapshot
        // sans le moindre appel réseau — c'est tout l'intérêt d'ADR-0015.
        if (stationSnapshotRepository.findByStationId(stationId).isEmpty()
                && examServiceClient.estProbablementInjoignable()) {
            log.warn("ADR-0015 : station {} non figée et exam-service réputé injoignable — "
                    + "affichage dégradé immédiat (pas de nouvelle tentative)", stationId);
            return NOM_INDISPONIBLE;
        }
        try {
            return resolveStationNom(examenId, stationId);
        } catch (RuntimeException e) {
            log.warn("ADR-0015 : intitulé de la station {} non résolu (examen {}) — affichage dégradé : {}",
                    stationId, examenId, e.getMessage());
            return NOM_INDISPONIBLE;
        }
    }

    /**
     * The grille's notable (leaf) items, keyed by {@code itemId}. Materialises on first use.
     *
     * <p><b>Presence in this map is the authority on what may be graded.</b> An item absent from it
     * is not notable — which makes the leaf guard local and unconditional, unlike
     * {@code saisirNotation:227}, whose {@code !isEmpty()} test skips the guard entirely when a
     * remote lookup comes back empty.
     *
     * @throws BusinessException if not yet snapshotted and exam-service cannot supply it.
     */
    @Transactional
    public Map<Long, ExamItemSnapshot> resolveItems(Long examenId, Long grilleId) {
        List<ExamItemSnapshot> stored = itemSnapshotRepository.findByGrilleId(grilleId);
        if (stored.isEmpty()) {
            stored = materialiser.materialiseItems(examenId, grilleId);
        }
        return stored.stream()
                .collect(Collectors.toMap(ExamItemSnapshot::getItemId, Function.identity()));
    }

    /**
     * Weighted score for one graded item — the single definition of the arithmetic.
     *
     * <p>Centralised so the two historical copies of {@code recalculerScoreFinal}
     * ({@code EvaluateurDashboardService:489}, {@code NotationReajustementService:130}) cannot drift
     * again; their javadoc claimed they "MUST stay identical" while they had already diverged.
     *
     * @throws BusinessException if {@code itemId} is not in the grille's notable set. <b>Never score
     *                           an unknown item raw</b> — that is exactly how an outage turned a
     *                           {@code valeur 1 × pondération 5} item into 1 point, and how a parent
     *                           criterion double-counts.
     */
    public float weigh(Map<Long, ExamItemSnapshot> items, Long itemId, Float valeur) {
        ExamItemSnapshot item = items.get(itemId);
        if (item == null) {
            throw new BusinessException(
                    "Item " + itemId + " ne fait pas partie des critères notables de cette grille "
                            + "(absent du snapshot ADR-0015). Score non recalculé plutôt que faussé.");
        }
        return item.weigh(valeur);
    }

    /**
     * #183 « dé-lancer » ({@code EN_COURS → CONFIGURE}) must drop the snapshot so a relaunch
     * re-copies it. Without this, an edited grille would be graded against the stale copy — the one
     * sharp edge of the write-once design.
     */
    @Transactional
    public void invalidateExam(Long examenId) {
        stationSnapshotRepository.deleteByExamenId(examenId);
        itemSnapshotRepository.deleteByExamenId(examenId);
        log.info("ADR-0015 : snapshot de définition invalidé pour l'examen {} (re-copie au prochain usage)", examenId);
    }

}
