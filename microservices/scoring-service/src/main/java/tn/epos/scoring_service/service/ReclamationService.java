package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.config.EvaluateurScopeChecker;
import tn.epos.scoring_service.dto.ReclamationDTO;
import tn.epos.scoring_service.dto.ReclamationRequest;
import tn.epos.scoring_service.dto.ReclamationResolveRequest;
import tn.epos.scoring_service.entities.Reclamation;
import tn.epos.scoring_service.entities.ReclamationStatus;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;
import tn.epos.scoring_service.repositories.IReclamationRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The responsable's complaint register (#136). Files student réclamations,
 * tracks their disposition and — for upheld ones — links the audited
 * réajustement ({@link NotationReajustementService}) that carried out the
 * correction.
 *
 * <p>Authorization (RESPONSABLE_MATIERE / SUPER_ADMIN, never ÉVALUATEUR) is
 * enforced at the controller via {@code @PreAuthorize}; this service only
 * resolves the caller's user id for attribution. This is deliberately NOT the
 * channel that mutates a score — that stays the guarded réajustement endpoint,
 * so the register never becomes a second back door to a locked notation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ReclamationService {

    private final IReclamationRepository reclamationRepository;
    private final IExamenParticipationRepository participationRepository;
    private final EvaluateurScopeChecker scopeChecker;
    /** #274 — le registre des réclamations est celui d'UNE épreuve, donc d'UNE matière. */
    private final MatiereAccessGuard matiereAccessGuard;

    /** Files a new complaint in state EN_ATTENTE, attributed to the caller. */
    public ReclamationDTO creer(ReclamationRequest req) {
        Long userId = requireCallerUserId();

        // #274 — on ne dépose pas une réclamation sur l'épreuve d'un collègue d'une autre
        // matière. `Reclamation` porte `examenId` en direct : aucune chaîne à remonter.
        matiereAccessGuard.checkExamenAccess(req.examenId());

        // The complaint must point at a real participation — the register is only
        // meaningful if it references an actual student result (same DB, cheap check).
        if (!participationRepository.existsById(req.participationId())) {
            throw new ResourceNotFoundException(
                    "Participation introuvable : " + req.participationId());
        }

        Reclamation reclamation = Reclamation.builder()
                .examenId(req.examenId())
                .participationId(req.participationId())
                .notationId(req.notationId())
                .objet(req.objet())
                .statut(ReclamationStatus.EN_ATTENTE)
                .createdByUserId(userId)
                .build();
        Reclamation saved = reclamationRepository.save(reclamation);

        log.info("Réclamation enregistrée id={} examen={} participation={} par user={}",
                saved.getId(), req.examenId(), req.participationId(), userId);
        return ReclamationDTO.fromEntity(saved);
    }

    /**
     * Decides a pending complaint. The statut must be a terminal decision
     * (ACCEPTEE or REJETEE); a complaint can only be decided once (guarded on
     * EN_ATTENTE) so a disposition is never silently overwritten.
     */
    public ReclamationDTO resoudre(Long id, ReclamationResolveRequest req) {
        Long userId = requireCallerUserId();

        if (req.statut() == ReclamationStatus.EN_ATTENTE) {
            throw new BusinessException(
                    "La décision doit être ACCEPTEE ou REJETEE (pas EN_ATTENTE).");
        }

        Reclamation reclamation = reclamationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Réclamation introuvable : " + id));

        // #274 — décider une réclamation engage la note d'un candidat : réservé au titulaire
        // de la matière. Vérifié AVANT la règle « déjà traitée », qui révélerait sinon l'état
        // d'une réclamation d'une autre matière.
        matiereAccessGuard.checkExamenAccess(reclamation.getExamenId());

        if (reclamation.getStatut() != ReclamationStatus.EN_ATTENTE) {
            throw new BusinessException(
                    "Réclamation " + id + " déjà traitée (" + reclamation.getStatut()
                            + ") — une réclamation ne se décide qu'une fois.");
        }

        reclamation.setStatut(req.statut());
        reclamation.setReponse(req.reponse());
        reclamation.setAdjustmentId(req.adjustmentId());
        reclamation.setResolvedByUserId(userId);
        reclamation.setResolvedAt(LocalDateTime.now());
        Reclamation saved = reclamationRepository.save(reclamation);

        log.info("Réclamation id={} décidée {} par user={} adjustment={}",
                id, req.statut(), userId, req.adjustmentId());
        return ReclamationDTO.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public List<ReclamationDTO> listerParExamen(Long examenId) {
        // #274 — lecture examen-clé : une réclamation nomme un candidat et contexte un litige
        // sur sa note. Ce n'est pas de la supervision agrégée, et le périmètre se vérifie en
        // une seule résolution, sans parcourir la liste.
        matiereAccessGuard.checkExamenAccess(examenId);
        return reclamationRepository.findByExamenIdOrderByCreatedAtDesc(examenId).stream()
                .map(ReclamationDTO::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReclamationDTO trouver(Long id) {
        Reclamation reclamation = reclamationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Réclamation introuvable : " + id));
        matiereAccessGuard.checkExamenAccess(reclamation.getExamenId());
        return ReclamationDTO.fromEntity(reclamation);
    }

    /**
     * The caller's auth user id, or 403 when it cannot be resolved — we refuse
     * to write an un-attributable row to the complaint register (same posture as
     * the réajustement channel).
     */
    private Long requireCallerUserId() {
        Long userId = scopeChecker.getCallerUserId();
        if (userId == null) {
            throw new AccessDeniedException(
                    "Identité de l'appelant introuvable — réclamation refusée.");
        }
        return userId;
    }
}
