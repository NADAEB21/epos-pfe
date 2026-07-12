package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    // Toutes les notations d'un examen, jointes jusqu'à l'étudiant (issue #90).
    // La chaîne notation -> assignment -> participation -> etudiant est traversée
    // en JOIN FETCH pour bâtir l'agrégat par étudiant sans N+1 sur les lazy.
    // Le filtre porte sur le FK logique ExamenParticipation.examen_id (snake_case
    // côté Java — référencé tel quel en JPQL, pas une derived query).
    @Query("SELECT n FROM Notation n " +
           "JOIN FETCH n.assignment a " +
           "JOIN FETCH a.participation p " +
           "JOIN FETCH p.etudiant e " +
           "WHERE p.examen_id = :examenId")
    List<Notation> findByExamenIdWithGraph(@Param("examenId") Long examenId);
}