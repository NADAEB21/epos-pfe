package tn.epos.scoring_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tn.epos.scoring_service.entities.RotationAssignment;
import tn.epos.scoring_service.repositories.IRotationAssignmentRepository;

import java.util.List;
import java.util.Optional;

@Service
public class RotationAssignmentService {

    @Autowired
    private IRotationAssignmentRepository repository;

    // Récupérer toutes les assignments
    public List<RotationAssignment> findAll() {
        return repository.findAll();
    }

    // Récupérer par ID
    public Optional<RotationAssignment> findById(Long id) {
        return repository.findById(id);
    }

    // Récupérer par rotation
    public List<RotationAssignment> findByRotation(Long rotationId) {
        return repository.findByRotationId(rotationId);
    }

    // Créer un assignment
    public RotationAssignment save(RotationAssignment assignment) {
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
            a.setRotation(details.getRotation());
            a.setParticipation(details.getParticipation());
            return repository.save(a);
        }).orElseThrow(() -> new RuntimeException("Assignment non trouvé avec l'id : " + id));
    }

    // Confirmer rapidement la présence
    public RotationAssignment confirmerPresence(Long id, boolean present) {
        RotationAssignment a = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Assignment non trouvé avec l'id : " + id));
        a.setPresenceConfirmee(present);
        return repository.save(a);
    }
}