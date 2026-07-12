package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.RotationAssignment;

import java.util.List;
import java.util.Optional;

@Repository
public interface IRotationAssignmentRepository extends JpaRepository<RotationAssignment, Long> {

    // Lister tous les assignments d'une rotation spécifique
    List<RotationAssignment> findByRotationId(Long rotationId);

    /**
     * Retrouve l'assignment d'une participation.
     * Clé de voûte du flux notation : participation → assignment → notation.*/
     Optional<RotationAssignment> findByParticipationId(Long participationId);
}