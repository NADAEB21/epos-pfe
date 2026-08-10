package tn.epos.scoring_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tn.epos.scoring_service.entities.StudentGroup;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.repositories.IStudentGroupRepository;

import java.util.List;
import java.util.Optional;

/**
 * Groupes d'étudiants à l'intérieur d'un lot.
 *
 * <p><b>#274 — écritures bornées à la matière</b>, résolue par {@code group → lot → examenId}.
 * Un groupe orphelin (sans lot) n'est rattachable à aucune matière : la garde échoue alors fermé,
 * ce qui est le bon défaut — un groupe hors lot est déjà exclu de la génération des rotations.
 */
@Service
public class StudentGroupService {

    @Autowired
    private IStudentGroupRepository studentGroupRepository;

    @Autowired
    private MatiereAccessGuard matiereAccessGuard;

    /** {@code group → lot → examenId}, ou {@code null} si le groupe est détaché. */
    private Long examenIdDe(StudentGroup group) {
        return (group != null && group.getLot() != null) ? group.getLot().getExamenId() : null;
    }

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
        matiereAccessGuard.checkExamenAccess(examenIdDe(group));
        return studentGroupRepository.save(group);
    }

    // Supprimer un groupe
    public void delete(Long id) {
        // Charger avant : `deleteById` ne dit pas de quel lot — donc de quel examen — il s'agit.
        studentGroupRepository.findById(id).ifPresent(group -> {
            matiereAccessGuard.checkExamenAccess(examenIdDe(group));
            studentGroupRepository.delete(group);
        });
    }

    // Mettre à jour un groupe
    public StudentGroup update(Long id, StudentGroup details) {
        return studentGroupRepository.findById(id).map(group -> {
            // Périmètre de DÉPART, puis d'ARRIVÉE si la charge utile réattribue le lot :
            // sinon déplacer un groupe vers le lot d'une autre matière contournerait la garde.
            matiereAccessGuard.checkExamenAccess(examenIdDe(group));
            if (details.getLot() != null) {
                matiereAccessGuard.checkExamenAccess(examenIdDe(details));
            }
            group.setNumeroGroupe(details.getNumeroGroupe());
            // #215 sémantique PATCH : le PUT ne peuple lot que si lotId est fourni
            // — le copier à null orphelinerait le groupe (exclu de la génération
            // des rotations).
            if (details.getLot() != null) {
                group.setLot(details.getLot());
            }
            return studentGroupRepository.save(group);
        }).orElseThrow(() -> new ResourceNotFoundException("StudentGroup non trouvé avec l'id : " + id));
    }
}