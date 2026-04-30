package tn.epos.scoring_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tn.epos.scoring_service.entities.StudentGroup;
import tn.epos.scoring_service.repositories.IStudentGroupRepository;

import java.util.List;
import java.util.Optional;

@Service
public class StudentGroupService {

    @Autowired
    private IStudentGroupRepository studentGroupRepository;

    // Récupérer tous les groupes
    public List<StudentGroup> findAll() {
        return studentGroupRepository.findAll();
    }

    // Récupérer un groupe par ID
    public Optional<StudentGroup> findById(Long id) {
        return studentGroupRepository.findById(id);
    }

    // Récupérer tous les groupes d'un lot
    public List<StudentGroup> findByLotId(Long lotId) {
        return studentGroupRepository.findByLotId(lotId);
    }

    // Créer un nouveau groupe
    public StudentGroup save(StudentGroup group) {
        return studentGroupRepository.save(group);
    }

    // Supprimer un groupe
    public void delete(Long id) {
        studentGroupRepository.deleteById(id);
    }

    // Mettre à jour un groupe
    public StudentGroup update(Long id, StudentGroup details) {
        return studentGroupRepository.findById(id).map(group -> {
            group.setNumeroGroupe(details.getNumeroGroupe());
            group.setLot(details.getLot());
            return studentGroupRepository.save(group);
        }).orElseThrow(() -> new RuntimeException("StudentGroup non trouvé avec l'id : " + id));
    }
}