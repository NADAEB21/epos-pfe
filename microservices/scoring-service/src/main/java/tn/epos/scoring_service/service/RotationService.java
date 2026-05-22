package tn.epos.scoring_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.exception.ResourceNotFoundException;
import tn.epos.scoring_service.repositories.IRotationRepository;

import java.util.List;
import java.util.Optional;

@Service
public class RotationService {

    @Autowired
    private IRotationRepository rotationRepository;

    // Récupérer toutes les rotations
    public List<Rotation> findAll() {
        return rotationRepository.findAll();
    }

    // Récupérer une rotation par ID
    public Optional<Rotation> findById(Long id) {
        return rotationRepository.findById(id);
    }

    // Récupérer toutes les rotations d'un groupe
    public List<Rotation> findByGroup(Long groupId) {
        return rotationRepository.findByStudentGroupId(groupId);
    }

    // Créer une rotation
    public Rotation save(Rotation rotation) {
        return rotationRepository.save(rotation);
    }

    // Supprimer une rotation
    public void delete(Long id) {
        rotationRepository.deleteById(id);
    }

    // Mettre à jour une rotation
    public Rotation update(Long id, Rotation details) {
        return rotationRepository.findById(id).map(rotation -> {
            rotation.setOrdrePassage(details.getOrdrePassage());
            rotation.setDebutCreneau(details.getDebutCreneau());
            rotation.setStatut(details.getStatut());
            rotation.setEvaluateurId(details.getEvaluateurId());
            rotation.setStudentGroup(details.getStudentGroup());
            return rotationRepository.save(rotation);
        }).orElseThrow(() -> new ResourceNotFoundException("Rotation non trouvée avec l'id : " + id));
    }
}