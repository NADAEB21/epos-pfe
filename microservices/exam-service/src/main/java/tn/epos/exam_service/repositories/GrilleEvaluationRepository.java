package tn.epos.exam_service.repositories;

import tn.epos.exam_service.entities.GrilleEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GrilleEvaluationRepository extends JpaRepository<GrilleEvaluation, Long> {

    // Trouver la grille d'une station (relation 1:1)
    Optional<GrilleEvaluation> findByStationId(Long stationId);

    // Charger la grille avec tous ses items en une requête
    @Query("SELECT g FROM GrilleEvaluation g LEFT JOIN FETCH g.items WHERE g.id = :id")
    Optional<GrilleEvaluation> findByIdWithItems(@Param("id") Long id);

    // Charger la grille d'une station avec ses items
    @Query("SELECT g FROM GrilleEvaluation g LEFT JOIN FETCH g.items WHERE g.station.id = :stationId")
    Optional<GrilleEvaluation> findByStationIdWithItems(@Param("stationId") Long stationId);

    // Vérifier si une station a déjà une grille
    boolean existsByStationId(Long stationId);
}
