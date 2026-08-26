package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ConflictException;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.config.EvaluateurScopeChecker;
import tn.epos.scoring_service.dto.BaremeDeliberationDTO;
import tn.epos.scoring_service.dto.BaremeDeliberationRequest;
import tn.epos.scoring_service.entities.BaremeDeliberation;
import tn.epos.scoring_service.entities.BaremeDeliberationOperation;
import tn.epos.scoring_service.entities.ExamGrilleSnapshot;
import tn.epos.scoring_service.entities.ExamItemSnapshot;
import tn.epos.scoring_service.entities.TypeOperationBareme;
import tn.epos.scoring_service.repositories.ExamGrilleSnapshotRepository;
import tn.epos.scoring_service.repositories.ExamItemSnapshotRepository;
import tn.epos.scoring_service.repositories.IBaremeDeliberationOperationRepository;
import tn.epos.scoring_service.repositories.IBaremeDeliberationRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ADR-0030 (issue #361) — écriture et historique du barème de délibération.
 * Le modèle de garde est celui du réajustement ADR-0013
 * ({@link NotationReajustementService}) : rôle au contrôleur, MATIÈRE ici
 * (#274), auteur depuis le JWT (échec fermé — jamais de version
 * inattribuable), motif obligatoire.
 *
 * <p>S'y ajoute la garde propre à la délibération : <b>examen clos seulement</b>
 * (D1). Le statut est lu en STRICT ({@link ExamServiceClient#getStatutStrict})
 * — exam-service muet ⇒ écriture refusée bruyamment, JAMAIS une garde ouverte
 * par défaut. « Clos » = {@code TERMINE} ou {@code ARCHIVE} : l'intention de
 * l'ADR est « jamais avant la fin de l'épreuve » ; le verrou institutionnel de
 * l'archivage est explicitement différé (#236/W12, ADR-0030 § non décidé).
 *
 * <p>Les versions sont IMMUABLES (D3) : ce service n'a ni update ni delete —
 * corriger, c'est écrire {@code version = max+1} ; revenir au barème d'origine,
 * c'est une version VIDE. La contrainte {@code UNIQUE(examen_id, version)} (V25)
 * arrête la course de deux POST concurrents.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class BaremeDeliberationService {

    /** ADR-0030 D1 « examen clos » — les deux états post-épreuve de StatutExamen. */
    private static final Set<String> STATUTS_CLOS = Set.of("TERMINE", "ARCHIVE");

    private final IBaremeDeliberationRepository baremeRepository;
    private final IBaremeDeliberationOperationRepository operationRepository;
    private final ExamItemSnapshotRepository itemSnapshotRepository;
    private final ExamGrilleSnapshotRepository grilleSnapshotRepository;
    private final EvaluateurScopeChecker scopeChecker;
    private final MatiereAccessGuard matiereAccessGuard;
    private final ExamServiceClient examServiceClient;
    private final BaremeDeliberationEngine engine;

    public BaremeDeliberationDTO creer(Long examenId, BaremeDeliberationRequest request) {
        Long userId = scopeChecker.getCallerUserId();
        if (userId == null) {
            // Pas d'identité exploitable → une version inattribuable ne s'écrit pas
            // (même refus que le réajustement ADR-0013).
            throw new AccessDeniedException(
                    "Identité de l'appelant introuvable — barème de délibération refusé.");
        }

        // #274 — le périmètre d'abord (précédent N6 : la garde « clos » vient APRÈS).
        matiereAccessGuard.checkExamenAccess(examenId);

        // D1 — examen clos seulement, statut lu en STRICT (fail-closed).
        String statut = examServiceClient.getStatutStrict(examenId);
        if (!STATUTS_CLOS.contains(statut)) {
            throw new ConflictException("L'examen " + examenId + " est " + statut
                    + " — le barème de délibération n'est possible qu'une fois l'examen clos "
                    + "(TERMINE ou ARCHIVE, ADR-0030).");
        }

        List<BaremeDeliberationRequest.OperationRequest> operations = request.operations();
        validerOperations(examenId, operations);

        Optional<BaremeDeliberation> courant =
                baremeRepository.findTopByExamenIdOrderByVersionDesc(examenId);
        if (courant.isPresent() && memesOperations(courant.get(), operations)) {
            throw new ConflictException("Ces opérations sont déjà exactement celles de la version "
                    + courant.get().getVersion()
                    + " (courante) — double application refusée (ADR-0030 D5). "
                    + "Écrire une nouvelle version n'a de sens que si le barème change.");
        }
        int version = courant.map(b -> b.getVersion() + 1).orElse(1);

        BaremeDeliberation saved = baremeRepository.save(BaremeDeliberation.builder()
                .examenId(examenId)
                .version(version)
                .motif(request.motif())
                .creePar(userId)
                .build());

        List<BaremeDeliberationOperation> savedOps = new ArrayList<>();
        for (BaremeDeliberationRequest.OperationRequest op : operations) {
            savedOps.add(operationRepository.save(BaremeDeliberationOperation.builder()
                    .baremeId(saved.getId())
                    .type(op.type())
                    .cibleItemId(op.cibleItemId())
                    .cibleStationId(op.cibleStationId())
                    .nouvelleEchelle(op.nouvelleEchelle())
                    .build()));
        }

        log.info("Barème de délibération v{} créé pour l'examen {} par user={} "
                        + "({} opération(s)) motif=\"{}\"",
                version, examenId, userId, savedOps.size(), request.motif());
        return BaremeDeliberationDTO.fromEntities(saved, savedOps);
    }

    /** Historique complet, version la plus récente d'abord (D3 : tout reste lisible). */
    @Transactional(readOnly = true)
    public List<BaremeDeliberationDTO> historique(Long examenId) {
        // #274 — même périmètre que les Résultats : la vue est examen-clé.
        matiereAccessGuard.checkExamenAccess(examenId);

        List<BaremeDeliberation> versions =
                baremeRepository.findByExamenIdOrderByVersionDesc(examenId);
        if (versions.isEmpty()) {
            return List.of();
        }
        Map<Long, List<BaremeDeliberationOperation>> opsParBareme = new HashMap<>();
        for (BaremeDeliberationOperation op : operationRepository.findByBaremeIdIn(
                versions.stream().map(BaremeDeliberation::getId).toList())) {
            opsParBareme.computeIfAbsent(op.getBaremeId(), k -> new ArrayList<>()).add(op);
        }
        return versions.stream()
                .map(v -> BaremeDeliberationDTO.fromEntities(
                        v, opsParBareme.getOrDefault(v.getId(), List.of())))
                .toList();
    }

    /**
     * Refus NOMINATIFS, ligne par ligne. Les cibles se valident contre le
     * SNAPSHOT (ADR-0030 D2) : {@code exam_item_snapshot} pour les critères
     * (feuilles par construction), {@code exam_grille_snapshot} pour les
     * stations. Un examen sans snapshot (pré-V19) n'a aucune cible définissable.
     */
    private void validerOperations(Long examenId,
                                   List<BaremeDeliberationRequest.OperationRequest> operations) {
        if (operations.isEmpty()) {
            return; // version « retour au barème du lancement » (D3)
        }

        List<ExamGrilleSnapshot> grilles = grilleSnapshotRepository.findByExamenId(examenId);
        Map<Long, ExamItemSnapshot> itemsParId = new HashMap<>();
        for (ExamItemSnapshot s : itemSnapshotRepository.findByExamenId(examenId)) {
            itemsParId.put(s.getItemId(), s);
        }
        if (grilles.isEmpty() && itemsParId.isEmpty()) {
            throw new BusinessException("L'examen " + examenId + " n'a pas de barème snapshoté "
                    + "(antérieur à V19) — aucune cible de délibération n'est définissable "
                    + "(ADR-0030 D2 : les cibles référencent le snapshot).");
        }
        Map<Long, Long> stationParGrille = new HashMap<>();
        Set<Long> stationIds = new HashSet<>();
        for (ExamGrilleSnapshot g : grilles) {
            stationParGrille.put(g.getGrilleId(), g.getStationId());
            stationIds.add(g.getStationId());
        }
        Map<Long, Double> valeurMaxParItem = engine.valeurMaxParItem(grilles);

        Set<Long> itemsCibles = new HashSet<>();
        Set<Long> stationsCibles = new HashSet<>();
        Set<Long> stationsDesItemsCibles = new HashSet<>();

        for (BaremeDeliberationRequest.OperationRequest op : operations) {
            switch (op.type()) {
                case EXCLURE_CRITERE -> {
                    exigeCibleItem(op);
                    stationsDesItemsCibles.add(
                            validerCibleItem(op, itemsParId, valeurMaxParItem, stationParGrille));
                    if (!itemsCibles.add(op.cibleItemId())) {
                        throw new BusinessException("Le critère " + op.cibleItemId()
                                + " est ciblé par plusieurs opérations — une seule par cible.");
                    }
                }
                case EXCLURE_STATION -> {
                    exigeCibleStation(op);
                    validerCibleStation(op.cibleStationId(), stationIds);
                    if (!stationsCibles.add(op.cibleStationId())) {
                        throw new BusinessException("La station " + op.cibleStationId()
                                + " est ciblée par plusieurs opérations — une seule par cible.");
                    }
                }
                case REPONDERER -> {
                    if (op.nouvelleEchelle() == null) {
                        throw new BusinessException(
                                "REPONDERER exige nouvelleEchelle (la nouvelle échelle, > 0).");
                    }
                    boolean surItem = op.cibleItemId() != null;
                    boolean surStation = op.cibleStationId() != null;
                    if (surItem == surStation) {
                        throw new BusinessException("REPONDERER cible SOIT un critère "
                                + "(cibleItemId) SOIT une station (cibleStationId), exactement un.");
                    }
                    if (surItem) {
                        stationsDesItemsCibles.add(
                                validerCibleItem(op, itemsParId, valeurMaxParItem, stationParGrille));
                        if (!itemsCibles.add(op.cibleItemId())) {
                            throw new BusinessException("Le critère " + op.cibleItemId()
                                    + " est ciblé par plusieurs opérations — une seule par cible.");
                        }
                    } else {
                        validerCibleStation(op.cibleStationId(), stationIds);
                        if (!stationsCibles.add(op.cibleStationId())) {
                            throw new BusinessException("La station " + op.cibleStationId()
                                    + " est ciblée par plusieurs opérations — une seule par cible.");
                        }
                    }
                }
            }
            if (op.type() != TypeOperationBareme.REPONDERER && op.nouvelleEchelle() != null) {
                throw new BusinessException("nouvelleEchelle n'a de sens que pour REPONDERER — "
                        + "préciser l'intention plutôt que d'ignorer le champ.");
            }
        }

        // Une station ciblée (exclue OU repondérée) ne se combine pas avec des
        // opérations critère chez elle : une station exclue sort entièrement du
        // calcul (les opérations critère y sont dénuées de sens), une station
        // repondérée rendrait l'ordre d'application ambigu. Un seul niveau à la fois.
        for (Long stationId : stationsCibles) {
            if (stationsDesItemsCibles.contains(stationId)) {
                throw new BusinessException("La station " + stationId + " est ciblée en même temps "
                        + "que certains de ses critères — combiner les deux niveaux est ambigu, "
                        + "choisir l'un ou l'autre.");
            }
        }
    }

    private void exigeCibleItem(BaremeDeliberationRequest.OperationRequest op) {
        if (op.cibleItemId() == null || op.cibleStationId() != null) {
            throw new BusinessException(
                    "EXCLURE_CRITERE cible un critère (cibleItemId seul, obligatoire).");
        }
    }

    private void exigeCibleStation(BaremeDeliberationRequest.OperationRequest op) {
        if (op.cibleStationId() == null || op.cibleItemId() != null) {
            throw new BusinessException(
                    "EXCLURE_STATION cible une station (cibleStationId seul, obligatoire).");
        }
    }

    /** @return la station du critère validé (pour les contrôles de combinaison). */
    private Long validerCibleItem(BaremeDeliberationRequest.OperationRequest op,
                                  Map<Long, ExamItemSnapshot> itemsParId,
                                  Map<Long, Double> valeurMaxParItem,
                                  Map<Long, Long> stationParGrille) {
        ExamItemSnapshot item = itemsParId.get(op.cibleItemId());
        if (item == null) {
            throw new BusinessException("Le critère " + op.cibleItemId()
                    + " n'existe pas dans le snapshot de cet examen — les cibles du barème "
                    + "de délibération référencent ce qui a réellement servi à noter (ADR-0030 D2).");
        }
        if (engine.maxDeItem(item, valeurMaxParItem) == null) {
            throw new BusinessException("Le critère " + op.cibleItemId()
                    + " n'a pas de valeur maximale au snapshot (valeur_max absente) — "
                    + "impossible de recalculer un dénominateur honnête, cible refusée.");
        }
        return stationParGrille.get(item.getGrilleId());
    }

    private void validerCibleStation(Long stationId, Set<Long> stationIds) {
        if (!stationIds.contains(stationId)) {
            throw new BusinessException("La station " + stationId
                    + " n'existe pas dans le snapshot de cet examen — les cibles du barème "
                    + "de délibération référencent ce qui a réellement servi à noter (ADR-0030 D2).");
        }
    }

    /** Les opérations demandées sont-elles EXACTEMENT celles de la version courante ? */
    private boolean memesOperations(BaremeDeliberation courant,
                                    List<BaremeDeliberationRequest.OperationRequest> demandees) {
        List<BaremeDeliberationOperation> existantes =
                operationRepository.findByBaremeId(courant.getId());
        if (existantes.size() != demandees.size()) {
            return false;
        }
        Set<List<Object>> a = new HashSet<>();
        for (BaremeDeliberationOperation op : existantes) {
            a.add(cle(op.getType(), op.getCibleItemId(), op.getCibleStationId(),
                    op.getNouvelleEchelle()));
        }
        Set<List<Object>> b = new HashSet<>();
        for (BaremeDeliberationRequest.OperationRequest op : demandees) {
            b.add(cle(op.type(), op.cibleItemId(), op.cibleStationId(), op.nouvelleEchelle()));
        }
        return a.equals(b);
    }

    private List<Object> cle(TypeOperationBareme type, Long item, Long station, Double echelle) {
        return List.of(Objects.toString(type), Objects.toString(item),
                Objects.toString(station), Objects.toString(echelle));
    }
}
