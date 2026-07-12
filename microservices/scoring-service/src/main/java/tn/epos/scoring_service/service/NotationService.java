package tn.epos.scoring_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.scoring_service.config.EvaluateurScopeChecker;
import tn.epos.scoring_service.dto.ExamenResultDTO;
import tn.epos.scoring_service.dto.StationScoreDTO;
import tn.epos.scoring_service.entities.Etudiant;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.entities.RotationAssignment;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.repositories.INotationRepository;
import tn.epos.scoring_service.repositories.IRotationAssignmentRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    // Résultats agrégés par étudiant pour un examen (issue #90). On part des
    // notations de l'examen (jointes jusqu'à l'étudiant), puis on regroupe par
    // participation : une ligne = un étudiant, ses scores par station + le total.
    // Lecture seule pour le responsable/admin — pas de filtre par évaluateur
    // (la vue Résultats est au niveau examen, pas au périmètre d'un correcteur).
    // @Transactional(readOnly) garde la session ouverte le temps de lire les
    // associations déjà fetch-join (étudiant), donc aucune LazyInit hors session.
    @Transactional(readOnly = true)
    public List<ExamenResultDTO> getResultatsByExamen(Long examenId) {
        List<Notation> notations = repository.findByExamenIdWithGraph(examenId);

        // Regroupe par participation en préservant l'ordre de première apparition.
        Map<Long, List<Notation>> parParticipation = new LinkedHashMap<>();
        for (Notation n : notations) {
            ExamenParticipation p = n.getAssignment().getParticipation();
            if (p == null) continue; // notation orpheline — ignorée
            parParticipation.computeIfAbsent(p.getId(), k -> new ArrayList<>()).add(n);
        }

        List<ExamenResultDTO> results = new ArrayList<>();
        for (Map.Entry<Long, List<Notation>> entry : parParticipation.entrySet()) {
            List<Notation> rows = entry.getValue();
            ExamenParticipation p = rows.get(0).getAssignment().getParticipation();
            Etudiant e = p.getEtudiant();

            List<StationScoreDTO> stations = rows.stream()
                    .map(StationScoreDTO::fromEntity)
                    .sorted(Comparator.comparing(
                            StationScoreDTO::stationId,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();

            double total = rows.stream()
                    .map(Notation::getScore_final)
                    .filter(s -> s != null)
                    .mapToDouble(Float::doubleValue)
                    .sum();

            results.add(new ExamenResultDTO(
                    p.getId(),
                    e != null ? e.getId() : null,
                    e != null ? e.getNumero_inscription() : null,
                    e != null ? e.getNom() : null,
                    e != null ? e.getPrenom() : null,
                    p.getNum_echantillon(),
                    total,
                    stations.size(),
                    stations));
        }

        // Tri par total décroissant — le classement se lit directement.
        results.sort(Comparator.comparingDouble(ExamenResultDTO::totalScore).reversed());
        return results;
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
