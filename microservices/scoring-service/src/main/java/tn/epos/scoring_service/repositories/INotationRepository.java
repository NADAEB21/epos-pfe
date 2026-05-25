package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.Notation;

import java.util.List;
import java.util.Optional;

@Repository
public interface INotationRepository extends JpaRepository<Notation, Long> {

    // Trouver la notation associée à une assignment spécifique
    Optional<Notation> findByAssignmentId(Long assignmentId);

    // Trouver les notations d'une station donnée (cross-service)
    List<Notation> findByStationId(Long stationId);

    // Trouver les notations d'une grille donnée (cross-service)
    List<Notation> findByGrilleId(Long grilleId);
}