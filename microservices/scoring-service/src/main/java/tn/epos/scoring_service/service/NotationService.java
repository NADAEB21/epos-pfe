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

    /** #274 — la vue Résultats est examen-clé : elle se lit dans SA matière. */
    @Autowired
    private MatiereAccessGuard matiereAccessGuard;

    // Récupérer toutes les notations — filtrées au périmètre de l'évaluateur (#91)
    public List<Notation> findAll() {
        List<Notation> all = repository.findAll();
        if (scopeChecker.peutLireHorsPerimetre()) {
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
        // #274 — lecture examen-clé, donc bornée : cette vue nomme chaque candidat et donne sa
        // note par station. Un responsable d'une autre matière n'a rien à y lire. Le périmètre
        // se vérifie en une résolution, sans parcourir la liste — c'est ce qui rend cette
        // lecture-ci bornable alors que les listes de notations restent hors périmètre.
        matiereAccessGuard.checkExamenAccess(examenId);

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
        if (scopeChecker.peutLireHorsPerimetre()) {
            return rows;
        }
        return rows.stream()
                .filter(n -> scopeChecker.isCaller(resolveEvaluateurId(n)))
                .toList();
    }

    // Récupérer les notations d'une grille (cross-service) — filtrées (#91)
    public List<Notation> findByGrille(Long grilleId) {
        List<Notation> rows = repository.findByGrilleId(grilleId);
        if (scopeChecker.peutLireHorsPerimetre()) {
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

    /**
     * Supprime une notation de manière sécurisée (#332).
     *
     * <p>Ce correctif répond à un bug de "faux-succès" où l'appel répondait 200 alors que
     * la ligne persistait en base. Bien que {@code deleteById(id)} effectue déjà un
     * {@code findById} en interne, l'ordre de suppression SQL pouvait être retardé ou
     * ignoré par le gestionnaire de persistance sans lever d'exception.
     *
     * <p>La solution implémente une stratégie de vérification d'effet :
     * <ol>
     *   <li>{@code @Transactional} : Regroupe l'action et sa vérification dans un même contexte.</li>
     *   <li>{@code flush()} : Force l'exécution immédiate de l'ordre DELETE vers la base de données.</li>
     *   <li><b>Vérification post-condition</b> : Relit l'existence de la ligne après le flush.
     *       Si la ligne survit, une {@link BusinessException} est levée pour transformer
     *       un échec silencieux en erreur explicite.</li>
     * </ol>
     *
     * @param id Identifiant de la notation à supprimer.
     * @throws ResourceNotFoundException si la notation n'existe pas.
     * @throws BusinessException si la suppression échoue techniquement (vérifié via existsById).
     */
    @Transactional
    public void delete(Long id) {
        Notation notation = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notation non trouvée avec l'id : " + id));

        repository.delete(notation);

        // Force l'envoi du DELETE SQL avant de tester la post-condition
        repository.flush();

        // Vérification de l'effet réel en base (La "leçon des sentinelles")
        if (repository.existsById(id)) {
            throw new BusinessException(
                    "La suppression de la notation " + id + " a échoué silencieusement : "
                            + "la ligne existe toujours après DELETE + flush (#332). "
                            + "Aucune donnée n'a été modifiée côté application ; contactez le support technique.");
        }
    }

    public Notation update(Long id, Notation details) {
        return repository.findById(id).map(n -> {
            scopeChecker.checkOwnership(resolveEvaluateurId(n));
            if (Boolean.TRUE.equals(n.getVerouillee())) {
                throw new BusinessException("Impossible de modifier une notation verrouillée.");
            }
            n.setScore_final(details.getScore_final());
            n.setIs_synced(details.getIs_synced());
            n.setTemps_additionnel(details.getTemps_additionnel());
            // #215 sémantique PATCH : le PUT ne peuple PAS stationId/grilleId
            // (tous deux NOT NULL) — les copier à null lève une
            // DataIntegrityViolationException (500). verouillee ne se pilote
            // que via l'endpoint /verrouiller, jamais via ce PUT.
            if (details.getStationId() != null) {
                n.setStationId(details.getStationId());
            }
            if (details.getGrilleId() != null) {
                n.setGrilleId(details.getGrilleId());
            }
            if (details.getVerouillee() != null) {
                n.setVerouillee(details.getVerouillee());
            }
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
