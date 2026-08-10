package tn.epos.scoring_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tn.epos.scoring_service.config.EvaluateurScopeChecker;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.repositories.IRotationRepository;

import java.util.List;
import java.util.Optional;

@Service
public class RotationService {

    @Autowired
    private IRotationRepository rotationRepository;

    @Autowired
    private EvaluateurScopeChecker scopeChecker;

    // Récupérer toutes les rotations — filtrées au périmètre de l'évaluateur (#91)
    public List<Rotation> findAll() {
        return scoped(rotationRepository.findAll());
    }

    // Récupérer une rotation par ID
    public Optional<Rotation> findById(Long id) {
        return rotationRepository.findById(id);
    }

    // Récupérer toutes les rotations d'un groupe — filtrées (#91)
    public List<Rotation> findByGroup(Long groupId) {
        return scoped(rotationRepository.findByStudentGroupId(groupId));
    }

    // Récupérer toutes les rotations d'une station (cross-service) — filtrées (#91)
    public List<Rotation> findByStation(Long stationId) {
        return scoped(rotationRepository.findByStationId(stationId));
    }

    // Filtre une liste de rotations au périmètre de l'évaluateur appelant.
    // Un appelant non contraint (SUPER_ADMIN / RESPONSABLE_MATIERE) voit tout.
    private List<Rotation> scoped(List<Rotation> rotations) {
        if (scopeChecker.peutLireHorsPerimetre()) {
            return rotations;
        }
        return rotations.stream()
                .filter(r -> scopeChecker.isCaller(r.getEvaluateurId()))
                .toList();
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
            // #215 sémantique PATCH : ne pas écraser à null les identifiants
            // structurels (évaluateur, station) ni le groupe — le PUT ne peuple
            // studentGroup que si studentGroupId est fourni.
            if (details.getEvaluateurId() != null) {
                rotation.setEvaluateurId(details.getEvaluateurId());
            }
            if (details.getStationId() != null) {
                rotation.setStationId(details.getStationId());
            }
            if (details.getStudentGroup() != null) {
                rotation.setStudentGroup(details.getStudentGroup());
            }
            return rotationRepository.save(rotation);
        }).orElseThrow(() -> new ResourceNotFoundException("Rotation non trouvée avec l'id : " + id));
    }
}