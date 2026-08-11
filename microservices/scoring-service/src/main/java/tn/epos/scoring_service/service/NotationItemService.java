package tn.epos.scoring_service.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.config.EvaluateurScopeChecker;
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

    /**
     * #218 — le MÊME contrôle de propriété que {@code NotationService}, appliqué
     * ici où il manquait. Ce service portait déjà le garde de VERROU (#23,
     * {@link #assertNotationNotLocked}) mais aucun garde de propriété : un
     * évaluateur refusé sur le chemin gardé du dashboard (#213) passait par
     * {@code PUT /api/notation-items/{id}} et modifiait la note d'un collègue.
     * Prouvé en direct : valeur 8,5 → 1,0 sur la station d'un autre, HTTP 200.
     */
    @Autowired
    private EvaluateurScopeChecker scopeChecker;

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
        assertProprietaire(item.getNotation());   // #218
        assertNotationNotLocked(item.getNotation());
        validateItemBelongsToParentGrille(item);
        return repository.save(item);
    }

    public void delete(Long id) {
        // #23 : ne pas contourner le verrou via l'endpoint item. On charge d'abord
        // le critère pour vérifier le verrou de sa notation parente avant suppression.
        repository.findById(id).ifPresent(item -> {
            assertProprietaire(item.getNotation());   // #218
            assertNotationNotLocked(item.getNotation());
        });
        repository.deleteById(id);
    }

    public NotationItem update(Long id, NotationItem details) {
        return repository.findById(id).map(item -> {
            // #23 : refuser toute modification d'un critère dont la notation parente
            // (celle d'origine) est verrouillée — avant même de réassigner le parent.
            assertProprietaire(item.getNotation());   // #218 — avant toute écriture
            assertNotationNotLocked(item.getNotation());
            item.setValeur(details.getValeur());
            item.setCommentaire(details.getCommentaire());
            // #215 sémantique PATCH : le PUT ne peuple PAS itemId/notation — les
            // copier à null orphelinerait le critère (exclu du recalcul du score).
            // On ne réassigne le parent que si un nouveau est explicitement fourni.
            if (details.getItemId() != null) {
                item.setItemId(details.getItemId());
            }
            if (details.getNotation() != null) {
                item.setNotation(details.getNotation());
            }
            // Et refuser aussi de déplacer le critère VERS une notation verrouillée
            // — ou vers celle d'un AUTRE évaluateur (#218 : sinon le déplacement
            // devient le contournement du garde qu'on vient de poser).
            assertProprietaire(item.getNotation());
            assertNotationNotLocked(item.getNotation());
            validateItemBelongsToParentGrille(item);
            return repository.save(item);
        }).orElseThrow(() -> new ResourceNotFoundException("NotationItem non trouvé avec l'id : " + id));
    }

    /**
     * #218 — un critère n'est modifiable que par l'évaluateur qui tient la station
     * de sa notation parente.
     *
     * <p>Ce service portait le garde de VERROU (#23) mais pas celui de PROPRIÉTÉ,
     * contrairement à son jumeau {@code NotationService} qui appelle
     * {@code scopeChecker.checkOwnership(...)} depuis toujours. Résultat : un
     * évaluateur refusé par le garde du dashboard (#213) obtenait le même effet
     * par {@code PUT /api/notation-items/{id}} — vérifié en direct, 8,5 → 1,0 sur
     * la station d'un collègue, et sans même être attribué.
     *
     * <p>On réutilise {@code EvaluateurScopeChecker} plutôt que d'écrire un second
     * contrôle : deux mécanismes de propriété finiraient par diverger.
     *
     * <p><b>#274 — le responsable ne passe plus.</b> La version précédente de ce Javadoc
     * justifiait l'exemption ainsi : « {@code isUnrestricted()} laisse passer responsable et
     * admin, dont la supervision est légitime ; l'auteur réel reste tracé par
     * {@code saisi_par} ». Les deux moitiés étaient fausses. La supervision est une
     * <b>LECTURE</b> (ADR-0018 D5) — modifier la note d'un examinateur n'en est pas une. Et
     * {@code saisi_par} n'était PAS écrit sur ce chemin : la note changeait et la base continuait
     * de désigner l'auteur précédent, donc une traçabilité fausse plutôt qu'absente. Le canal du
     * responsable est le <b>réajustement audité</b> (ADR-0013 partie 2), motivé et attribué — et
     * c'est déjà le seul que l'IHM web utilise.
     *
     * <p>Aucune garde de MATIÈRE n'est ajoutée ici, volontairement : le responsable étant
     * désormais refusé quelle que soit sa matière, elle n'aurait rien à décider. Seul
     * {@code SUPER_ADMIN} traverse encore, divergence ADR-0018 D5 assumée et différée.
     *
     * <p>Compatible ADR-0017 : la propriété est lue sur
     * {@code rotation.evaluateurId}, que la suppléance met à jour — un remplaçant
     * est donc autorisé sans traitement particulier.
     */
    private void assertProprietaire(Notation notation) {
        if (notation == null || notation.getId() == null) return;
        Notation parent = notationRepository.findById(notation.getId()).orElse(null);
        if (parent == null || parent.getAssignment() == null
                || parent.getAssignment().getRotation() == null) {
            // #274 — chaîne rompue : on échoue FERMÉ, on ne renonce plus au contrôle.
            // La version précédente rendait la main ici (« rien à comparer »), ce qui était
            // survivable tant que responsable et admin étaient exemptés de toute façon. Depuis
            // que cette garde décide vraiment, ce `return` serait devenu LE contournement :
            // une notation sans affectation aurait été modifiable par tout porteur du rôle.
            // `checkOwnership(null)` refuse tout appelant borné et ne laisse passer que
            // l'exemption d'écriture explicite.
            scopeChecker.checkOwnership(null);
            return;
        }
        scopeChecker.checkOwnership(parent.getAssignment().getRotation().getEvaluateurId());
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
