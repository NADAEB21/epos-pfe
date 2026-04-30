package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.RotationAssignment;

import java.util.List;

@Repository
public interface IRotationAssignmentRepository extends JpaRepository<RotationAssignment, Long> {

    // Lister tous les assignments d'une rotation spécifique
    List<RotationAssignment> findByRotationId(Long rotationId);

    // Lister tous les assignments d'une participation spécifique
    List<RotationAssignment> findByParticipationId(Long participationId);
}