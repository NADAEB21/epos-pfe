package tn.epos.scoring_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tn.epos.scoring_service.config.EvaluateurScopeChecker;
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.entities.RotationAssignment;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.repositories.INotationRepository;
import tn.epos.scoring_service.repositories.IRotationAssignmentRepository;

import java.util.List;
import java.util.Optional;

@Service
public class NotationService {

    @Autowired
    private INotationRepository repository;

    @Autowired
    private IRotationAssignmentRepository assignmentRepository;

    @Autowired
    private EvaluateurScopeChecker scopeChecker;

    // Récupérer toutes les notations — filtrées au périmètre de l'évaluateur (#91)
    public List<Notation> findAll() {
        List<Notation> all = repository.findAll();
        if (scopeChecker.isUnrestricted()) {
            return all;
        }
        return all.stream()
                .filter(n -> scopeChecker.isCaller(resolveEvaluateurId(n)))
                .toList();
    }

    // Récupérer par ID
    public Optional<Notation> findById(Long id) {
        return repository.findById(id);
    }

    // Récupérer par assignment
    public Optional<Notation> findByAssignment(Long assignmentId) {
        return repository.findByAssignmentId(assignmentId);
    }

    // Récupérer les notations d'une station (cross-service) — filtrées (#91)
    public List<Notation> findByStation(Long stationId) {
        List<Notation> rows = repository.findByStationId(stationId);
        if (scopeChecker.isUnrestricted()) {
            return rows;
        }
        return rows.stream()
                .filter(n -> scopeChecker.isCaller(resolveEvaluateurId(n)))
                .toList();
    }

    // Récupérer les notations d'une grille (cross-service) — filtrées (#91)
    public List<Notation> findByGrille(Long grilleId) {
        List<Notation> rows = repository.findByGrilleId(grilleId);
        if (scopeChecker.isUnrestricted()) {
            return rows;
        }
        return rows.stream()
                .filter(n -> scopeChecker.isCaller(resolveEvaluateurId(n)))
                .toList();
    }

    // Créer une notation. Lie l'assignment fourni (sinon la notation serait
    // orpheline et son périmètre irrésoluble) puis vérifie que l'évaluateur
    // appelant possède bien la rotation (#85, ADR 0007). Un appelant non
    // contraint (SUPER_ADMIN / RESPONSABLE_MATIERE) passe sans assignment.
    public Notation save(Notation notation, Long assignmentId) {
        if (assignmentId != null) {
            RotationAssignment assignment = assignmentRepository.findById(assignmentId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Assignment non trouvé avec l'id : " + assignmentId));
            notation.setAssignment(assignment);
            scopeChecker.checkOwnership(resolveEvaluateurId(notation));
        } else {
            // Pas d'assignment : un évaluateur ne peut pas créer une notation
            // hors périmètre (checkOwnership(null) -> 403 pour un appelant contraint).
            scopeChecker.checkOwnership(null);
        }
        return repository.save(notation);
    }

    // Supprimer une notation
    public void delete(Long id) {
        repository.deleteById(id);
    }

    public Notation update(Long id, Notation details) {
        return repository.findById(id).map(n -> {
            scopeChecker.checkOwnership(resolveEvaluateurId(n));
            if (Boolean.TRUE.equals(n.getVerouillee())) {
                throw new BusinessException("Impossible de modifier une notation verrouillée.");
            }
            n.setScore_final(details.getScore_final());
            n.setIs_synced(details.getIs_synced());
            n.setVerouillee(details.getVerouillee());
            n.setTemps_additionnel(details.getTemps_additionnel());
            n.setStationId(details.getStationId());
            n.setGrilleId(details.getGrilleId());
            // Tu peux aussi mettre à jour l'affectation si nécessaire :
            // n.setAssignment(details.getAssignment());
            return repository.save(n);
        }).orElseThrow(() -> new ResourceNotFoundException("Notation non trouvée avec l'id : " + id));
    }

    // Verrouiller une notation — seul l'évaluateur propriétaire (ou un appelant
    // non contraint) peut verrouiller (#85, ADR 0007).
    public Notation verrouiller(Long id) {
        Notation n = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notation non trouvée avec l'id : " + id));
        scopeChecker.checkOwnership(resolveEvaluateurId(n));
        n.setVerouillee(true);
        return repository.save(n);
    }

    // Résout l'évaluateur propriétaire via Notation -> RotationAssignment ->
    // Rotation.evaluateurId. Retourne null si la chaîne est incomplète
    // (notation orpheline / rotation sans évaluateur) — traité comme hors
    // périmètre pour un appelant contraint.
    private Long resolveEvaluateurId(Notation notation) {
        if (notation == null || notation.getAssignment() == null
                || notation.getAssignment().getRotation() == null) {
            return null;
        }
        return notation.getAssignment().getRotation().getEvaluateurId();
    }
}
