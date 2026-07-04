package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.config.EvaluateurScopeChecker;
import tn.epos.scoring_service.dto.NotationAdjustmentDTO;
import tn.epos.scoring_service.dto.ReajustementRequest;
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

    private static final String TYPE_BINAIRE = "BINAIRE";

    private final INotationRepository notationRepository;
    private final INotationItemRepository notationItemRepository;
    private final INotationAdjustmentRepository adjustmentRepository;
    private final ExamServiceClient examServiceClient;
    private final EvaluateurScopeChecker scopeChecker;

    /**
     * Applies an audited réajustement and returns the persisted adjustment row.
     * Authorization (RESPONSABLE_MATIERE / SUPER_ADMIN only) is enforced at the
     * controller via {@code @PreAuthorize}; here we only resolve the caller's
     * user id for the audit trail.
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
     * Recompute {@code score_final} as the weighted sum of the notation's items —
     * canonical copy of {@code EvaluateurDashboardService.recalculerScoreFinal}
     * (BINAIRE items count {@code valeur × pondération}, others count raw
     * {@code valeur}). The two will be unified when config/service duplication is
     * folded into epos-common (#68); until then they MUST stay identical so a
     * réajustement produces the same total the évaluateur's grading would.
     */
    private void recalculerScoreFinal(Notation notation) {
        List<NotationItem> items = notationItemRepository.findByNotationId(notation.getId());
        Map<Long, ExamServiceClient.ItemInfo> infos =
                examServiceClient.getItemInfosForGrille(notation.getGrilleId());
        float score = 0f;
        for (NotationItem ni : items) {
            ExamServiceClient.ItemInfo info = infos.get(ni.getItemId());
            float valeur = ni.getValeur() != null ? ni.getValeur() : 0f;
            score += (info != null && TYPE_BINAIRE.equals(info.type()))
                    ? valeur * (float) info.ponderation()
                    : valeur;
        }
        notation.setScore_final(score);
        notationRepository.save(notation);
    }
}
