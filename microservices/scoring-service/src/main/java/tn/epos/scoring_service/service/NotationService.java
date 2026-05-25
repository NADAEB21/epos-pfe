package tn.epos.scoring_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tn.epos.scoring_service.entities.Notation;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.repositories.INotationRepository;

import java.util.List;
import java.util.Optional;

@Service
public class NotationService {

    @Autowired
    private INotationRepository repository;

    // Récupérer toutes les notations
    public List<Notation> findAll() {
        return repository.findAll();
    }

    // Récupérer par ID
    public Optional<Notation> findById(Long id) {
        return repository.findById(id);
    }

    // Récupérer par assignment
    public Optional<Notation> findByAssignment(Long assignmentId) {
        return repository.findByAssignmentId(assignmentId);
    }

    // Récupérer les notations d'une station (cross-service)
    public List<Notation> findByStation(Long stationId) {
        return repository.findByStationId(stationId);
    }

    // Récupérer les notations d'une grille (cross-service)
    public List<Notation> findByGrille(Long grilleId) {
        return repository.findByGrilleId(grilleId);
    }

    // Créer une notation
    public Notation save(Notation notation) {
        // Logique métier : si la notation est verrouillée, on ne devrait pas la modifier
        return repository.save(notation);
    }

    // Supprimer une notation
    public void delete(Long id) {
        repository.deleteById(id);
    }

    public Notation update(Long id, Notation details) {
        return repository.findById(id).map(n -> {
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

    // Verrouiller une notation
    public Notation verrouiller(Long id) {
        Notation n = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notation non trouvée avec l'id : " + id));
        n.setVerouillee(true);
        return repository.save(n);
    }
}