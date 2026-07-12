package tn.epos.scoring_service.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.entities.NotationItem;
import tn.epos.scoring_service.repositories.INotationItemRepository;
import tn.epos.scoring_service.repositories.INotationRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class NotationItemService {

    private static final Logger log = LoggerFactory.getLogger(NotationItemService.class);

    @Autowired
    private INotationItemRepository repository;

    @Autowired
    private INotationRepository notationRepository;

    @Autowired
    private ExamServiceClient examServiceClient;

    public List<NotationItem> findAll() {
        return repository.findAll();
    }

    public List<NotationItem> findByNotation(Long notationId) {
        return repository.findByNotationId(notationId);
    }

    public Optional<NotationItem> findById(Long id) {
        return repository.findById(id);
    }

    public NotationItem save(NotationItem item) {
        assertNotationNotLocked(item.getNotation());
        validateItemBelongsToParentGrille(item);
        return repository.save(item);
    }

    public void delete(Long id) {
        // #23 : ne pas contourner le verrou via l'endpoint item. On charge d'abord
        // le critère pour vérifier le verrou de sa notation parente avant suppression.
        repository.findById(id).ifPresent(item -> assertNotationNotLocked(item.getNotation()));
        repository.deleteById(id);
    }

    public NotationItem update(Long id, NotationItem details) {
        return repository.findById(id).map(item -> {
            // #23 : refuser toute modification d'un critère dont la notation parente
            // (celle d'origine) est verrouillée — avant même de réassigner le parent.
            assertNotationNotLocked(item.getNotation());
            item.setItemId(details.getItemId());
            item.setValeur(details.getValeur());
            item.setCommentaire(details.getCommentaire());
            item.setNotation(details.getNotation());
            // Et refuser aussi de déplacer le critère VERS une notation verrouillée.
            assertNotationNotLocked(item.getNotation());
            validateItemBelongsToParentGrille(item);
            return repository.save(item);
        }).orElseThrow(() -> new ResourceNotFoundException("NotationItem non trouvé avec l'id : " + id));
    }

    /**
     * Enforces #23: a locked ({@code verouillee}) notation is final — its critères
     * cannot be created, edited, moved or deleted through the item endpoints. The
     * ONLY sanctioned way to change a locked score is the audited réajustement
     * channel (réclamation flow), never this back door. No-op when the parent link
     * is absent (defensive for detached/legacy items).
     */
    private void assertNotationNotLocked(Notation notation) {
        if (notation == null || notation.getId() == null) return;
        Long notationId = notation.getId();
        Notation parent = notationRepository.findById(notationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notation parente introuvable : " + notationId));
        if (Boolean.TRUE.equals(parent.getVerouillee())) {
            throw new BusinessException(
                    "Notation " + notationId + " verrouillée : un critère d'une notation "
                            + "verrouillée ne peut être modifié que via un réajustement (réclamation).");
        }
    }

    /**
     * Enforces #84: a NotationItem's item_id must belong to the parent Notation's
     * grille. Skipped (with a warn log) when the link is incomplete — defensive
     * for legacy rows where Notation.grilleId is null.
     */
    private void validateItemBelongsToParentGrille(NotationItem item) {
        if (item.getNotation() == null || item.getNotation().getId() == null) return;

        Long notationId = item.getNotation().getId();
        Notation parent = notationRepository.findById(notationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notation parente introuvable : " + notationId));

        Long grilleId = parent.getGrilleId();
        if (grilleId == null) {
            log.warn("Validation cross-grille ignorée : notation {} sans grille_id", notationId);
            return;
        }
        if (item.getItemId() == null) {
            throw new BusinessException("item_id est requis sur le NotationItem");
        }

        Set<Long> allowedItems = examServiceClient.getItemIdsForGrille(grilleId);
        if (allowedItems.isEmpty()) {
            // exam-service indisponible → on laisse passer pour ne pas bloquer
            log.warn("Validation cross-grille ignorée (exam-service indisponible) — item {}",
                    item.getItemId());
            return;
        }
        if (!allowedItems.contains(item.getItemId())) {
            throw new BusinessException(
                    "cross-grille refusé : L'item " + item.getItemId()
                            + " n'appartient pas à la grille " + grilleId);
        }
    }
}
