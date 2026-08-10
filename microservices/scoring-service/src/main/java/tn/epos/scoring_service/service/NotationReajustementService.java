package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.config.EvaluateurScopeChecker;
import tn.epos.scoring_service.dto.NotationAdjustmentDTO;
import tn.epos.scoring_service.dto.ReajustementRequest;
import tn.epos.scoring_service.entities.ExamItemSnapshot;
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.entities.NotationAdjustment;
import tn.epos.scoring_service.entities.NotationItem;
import tn.epos.scoring_service.repositories.INotationAdjustmentRepository;
import tn.epos.scoring_service.repositories.INotationItemRepository;
import tn.epos.scoring_service.repositories.INotationRepository;

import java.util.List;
import java.util.Map;

/**
 * ADR-0013 Part 2 — the ONE sanctioned channel that may change a locked
 * ({@code verouillee}) notation. Used by a {@code RESPONSABLE_MATIERE} /
 * {@code SUPER_ADMIN} to fix a genuine mistake on a student réclamation.
 *
 * <p>The change is applied <b>through the guarded door, never by unlocking</b>:
 * this service writes the item/score directly and the notation stays locked
 * the whole time. There is no unlock→edit→relock window (which Part 1 closed on
 * every ordinary path — {@code NotationItemService} rejects any write touching a
 * locked parent). Every call, in one transaction, records an immutable
 * {@link NotationAdjustment} row (old→new, motif, who, when) <i>synchronously</i>
 * — the audit row and the mutation commit together or not at all.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class NotationReajustementService {

    private final INotationRepository notationRepository;
    private final INotationItemRepository notationItemRepository;
    private final INotationAdjustmentRepository adjustmentRepository;
    private final EvaluateurScopeChecker scopeChecker;
    /** #274 — le réajustement est le canal du responsable : il se borne à SA matière. */
    private final MatiereAccessGuard matiereAccessGuard;

    /** ADR-0015 — définition figée : seule source des pondérations et du calcul du score. */
    private final ExamDefinitionSnapshotService examDefinitionSnapshot;

    /**
     * Applies an audited réajustement and returns the persisted adjustment row.
     *
     * <p>Le rôle (RESPONSABLE_MATIERE / SUPER_ADMIN) est vérifié au contrôleur par
     * {@code @PreAuthorize} ; la MATIÈRE l'est ici (#274). Les deux sont nécessaires : le rôle nu
     * ne dit pas de quelle matière on est titulaire.
     *
     * <p>Depuis #274 c'est le <b>seul</b> chemin par lequel un responsable modifie une note —
     * l'écriture directe ({@code PUT /api/notation-items/{id}}, {@code PUT /api/notations/{id}})
     * lui est fermée. Ce canal-ci est motivé, attribué et historisé (ADR-0013 partie 2) ; il porte
     * donc d'autant plus la charge de vérifier que la note appartient bien à SA matière.
     */
    public NotationAdjustment reajuster(Long notationId, ReajustementRequest req) {
        Long userId = scopeChecker.getCallerUserId();
        if (userId == null) {
            // No usable userId claim → we cannot attribute the audit row, so we
            // refuse rather than write an un-attributable change to a locked note.
            throw new AccessDeniedException(
                    "Identité de l'appelant introuvable — réajustement refusé.");
        }

        Notation notation = notationRepository.findById(notationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notation non trouvée avec l'id : " + notationId));

        // #274 — la note relève-t-elle d'une épreuve de MA matière ? Échec fermé si la chaîne
        // vers l'examen est rompue : une note qu'on ne sait pas rattacher ne se réajuste pas.
        matiereAccessGuard.checkExamenAccess(notation.resolveExamenId());

        float ancienScore = notation.getScore_final() != null ? notation.getScore_final() : 0f;

        Float ancienneValeur = null;
        Float nouvelleValeurItem = null;

        if (req.itemId() != null) {
            // Réajustement d'un critère : on écrit la valeur directement (la
            // notation reste verrouillée) puis on recalcule le score total avec
            // la MÊME formule pondérée que la saisie évaluateur, sinon le total
            // divergerait de ce que la notation produisait.
            NotationItem item = notationItemRepository
                    .findByNotationIdAndItemId(notationId, req.itemId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Critère " + req.itemId() + " introuvable pour la notation " + notationId));
            ancienneValeur = item.getValeur();
            item.setValeur(req.nouvelleValeur());
            notationItemRepository.save(item);
            nouvelleValeurItem = req.nouvelleValeur();
            recalculerScoreFinal(notation);
        } else {
            // Réajustement du total directement (notation sans détail par critère
            // ou correction globale demandée par la réclamation).
            notation.setScore_final(req.nouvelleValeur());
            notationRepository.save(notation);
        }

        float nouveauScore = notation.getScore_final() != null ? notation.getScore_final() : 0f;

        NotationAdjustment adjustment = NotationAdjustment.builder()
                .notationId(notationId)
                .itemId(req.itemId())
                .ancienneValeur(ancienneValeur)
                .nouvelleValeur(nouvelleValeurItem)
                .ancienScore(ancienScore)
                .nouveauScore(nouveauScore)
                .motif(req.motif())
                .adjustedByUserId(userId)
                .build();
        NotationAdjustment saved = adjustmentRepository.save(adjustment);

        log.info("Réajustement notation={} item={} {}→{} (score {}→{}) par user={} motif=\"{}\"",
                notationId, req.itemId(), ancienneValeur, nouvelleValeurItem,
                ancienScore, nouveauScore, userId, req.motif());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<NotationAdjustmentDTO> historique(Long notationId) {
        return adjustmentRepository.findByNotationIdOrderByAdjustedAtDesc(notationId).stream()
                .map(NotationAdjustmentDTO::fromEntity)
                .toList();
    }

    /**
     * Recompute {@code score_final} as the weighted sum of the notation's items.
     *
     * <p><b>ADR-0015 — l'arithmétique n'est plus dupliquée.</b> Cette copie et celle de
     * {@code EvaluateurDashboardService} devaient « rester identiques » jusqu'à #68 ; elles avaient
     * déjà divergé sans que personne ne le voie : celle-ci protégeait un {@code valeur} nul, l'autre
     * déballait le {@code Float} directement et provoquait donc un <b>NPE sur le chemin de
     * notation</b>. Les deux délèguent maintenant à {@link ExamDefinitionSnapshotService#weigh},
     * seule définition du calcul — la divergence ne peut plus se reproduire.
     */
    private void recalculerScoreFinal(Notation notation) {
        List<NotationItem> items = notationItemRepository.findByNotationId(notation.getId());
        Map<Long, ExamItemSnapshot> definition =
                examDefinitionSnapshot.resolveItems(examenIdDe(notation), notation.getGrilleId());
        float score = 0f;
        for (NotationItem ni : items) {
            score += examDefinitionSnapshot.weigh(definition, ni.getItemId(), ni.getValeur());
        }
        notation.setScore_final(score);
        notationRepository.save(notation);
    }

    /** Chemin {@code Notation → assignment → participation → examen_id} (ADR-0015). */
    private Long examenIdDe(Notation notation) {
        return (notation.getAssignment() != null && notation.getAssignment().getParticipation() != null)
                ? notation.getAssignment().getParticipation().getExamen_id()
                : null;
    }
}
