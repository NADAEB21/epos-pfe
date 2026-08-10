package tn.epos.scoring_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tn.epos.scoring_service.config.EvaluateurScopeChecker;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationAssignment;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;
import tn.epos.scoring_service.repositories.IRotationAssignmentRepository;
import tn.epos.scoring_service.repositories.IRotationRepository;

import java.util.List;
import java.util.Optional;

@Service
public class RotationAssignmentService {

    @Autowired
    private IRotationAssignmentRepository repository;

    @Autowired
    private IRotationRepository rotationRepository;

    @Autowired
    private IExamenParticipationRepository participationRepository;

    @Autowired
    private EvaluateurScopeChecker scopeChecker;

    // Récupérer toutes les assignments — filtrées au périmètre de l'évaluateur (#91)
    public List<RotationAssignment> findAll() {
        return scoped(repository.findAll());
    }

    // Récupérer par ID
    public Optional<RotationAssignment> findById(Long id) {
        return repository.findById(id);
    }

    // Récupérer par rotation — filtrées (#91)
    public List<RotationAssignment> findByRotation(Long rotationId) {
        return scoped(repository.findByRotationId(rotationId));
    }

    // Filtre une liste d'assignments au périmètre de l'évaluateur appelant via
    // RotationAssignment -> Rotation.evaluateurId. Un appelant non contraint
    // (SUPER_ADMIN / RESPONSABLE_MATIERE) voit tout.
    private List<RotationAssignment> scoped(List<RotationAssignment> assignments) {
        if (scopeChecker.peutLireHorsPerimetre()) {
            return assignments;
        }
        return assignments.stream()
                .filter(a -> scopeChecker.isCaller(
                        a.getRotation() != null ? a.getRotation().getEvaluateurId() : null))
                .toList();
    }

    // Créer un assignment. Lie la rotation parente (et la participation) à
    // partir des ids fournis : sans ce lien la chaîne d'appartenance
    // Notation -> RotationAssignment -> Rotation.evaluateurId resterait brisée
    // et le périmètre évaluateur (#85, #91) serait inopérant sur les données
    // créées via l'API.
    public RotationAssignment save(RotationAssignment assignment, Long rotationId, Long participationId) {
        if (rotationId != null) {
            Rotation rotation = rotationRepository.findById(rotationId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Rotation non trouvée avec l'id : " + rotationId));
            assignment.setRotation(rotation);
        }
        if (participationId != null) {
            ExamenParticipation participation = participationRepository.findById(participationId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Participation non trouvée avec l'id : " + participationId));
            assignment.setParticipation(participation);
        }
        return repository.save(assignment);
    }

    // Supprimer un assignment
    public void delete(Long id) {
        repository.deleteById(id);
    }

    // Mettre à jour un assignment
    public RotationAssignment update(Long id, RotationAssignment details) {
        return repository.findById(id).map(a -> {
            a.setPresenceConfirmee(details.getPresenceConfirmee());
            a.setTempsAdditionnel(details.getTempsAdditionnel());
            // #215 sémantique PATCH : le PUT ne peuple PAS rotation/participation
            // — les copier à null briserait la chaîne
            // Notation -> Assignment -> Rotation.evaluateurId (note invisible au périmètre).
            if (details.getRotation() != null) {
                a.setRotation(details.getRotation());
            }
            if (details.getParticipation() != null) {
                a.setParticipation(details.getParticipation());
            }
            return repository.save(a);
        }).orElseThrow(() -> new ResourceNotFoundException("Assignment non trouvé avec l'id : " + id));
    }

    // Confirmer rapidement la présence
    public RotationAssignment confirmerPresence(Long id, boolean present) {
        RotationAssignment a = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Assignment non trouvé avec l'id : " + id));
        a.setPresenceConfirmee(present);
        return repository.save(a);
    }
}