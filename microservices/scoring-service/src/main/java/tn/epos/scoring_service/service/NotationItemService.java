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
        validateItemBelongsToParentGrille(item);
        return repository.save(item);
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }

    public NotationItem update(Long id, NotationItem details) {
        return repository.findById(id).map(item -> {
            item.setItem_id(details.getItem_id());
            item.setValeur(details.getValeur());
            item.setCommentaire(details.getCommentaire());
            item.setNotation(details.getNotation());
            validateItemBelongsToParentGrille(item);
            return repository.save(item);
        }).orElseThrow(() -> new ResourceNotFoundException("NotationItem non trouvé avec l'id : " + id));
    }

    /**
     * Enforces #84: a NotationItem's item_id must belong to the parent Notation's
     * grille. Skipped (with a warn log) when the link is incomplete — defensive
     * for legacy rows where Notation.grilleId is null.
     */
    private void validateItemBelongsToParentGrille(NotationItem item) {
        if (item.getNotation() == null || item.getNotation().getId() == null) {
            // No parent reference — let downstream JPA constraint handle it.
            return;
        }
        Long notationId = item.getNotation().getId();
        Notation parent = notationRepository.findById(notationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notation parente introuvable avec l'id : " + notationId));

        Long grilleId = parent.getGrilleId();
        if (grilleId == null) {
            log.warn("Cross-grille check skipped: notation {} has null grille_id", notationId);
            return;
        }
        if (item.getItem_id() == null) {
            throw new BusinessException("item_id est requis sur le NotationItem");
        }

        Set<Long> allowedItems = examServiceClient.getItemIdsForGrille(grilleId);
        if (!allowedItems.contains(item.getItem_id())) {
            throw new BusinessException(
                    "L'item " + item.getItem_id()
                            + " n'appartient pas à la grille " + grilleId
                            + " de la notation parente (cross-grille refusé).");
        }
    }
}
