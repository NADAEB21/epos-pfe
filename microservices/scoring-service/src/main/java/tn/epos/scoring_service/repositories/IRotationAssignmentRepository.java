package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.RotationAssignment;

import java.util.List;
import java.util.Optional;

@Repository
public interface IRotationAssignmentRepository extends JpaRepository<RotationAssignment, Long> {

    // Lister tous les assignments d'une rotation spécifique
    List<RotationAssignment> findByRotationId(Long rotationId);

    long countByRotationId(Long rotationId);

    /**
     * Retrouve l'assignment d'une participation POUR UNE STATION donnée.
     *
     * <p><b>#203 — cardinalité (1-N, pas 1-1).</b> Une participation (= un étudiant
     * dans un examen) a UN assignment PAR station du circuit OSCE : elle en a donc
     * autant qu'il y a de stations. L'ancien {@code findByParticipationId} renvoyait
     * un {@code Optional} et jetait {@code NonUniqueResultException} (500) dès qu'un
     * étudiant était passé à une 2ᵉ station — c'est-à-dire le cas normal. La clé
     * unique d'un passage est le couple <b>(participation, station)</b>, résolu ici
     * via {@code RotationAssignment → Rotation.stationId}.
     */
    @Query("SELECT a FROM RotationAssignment a "
            + "WHERE a.participation.id = :participationId AND a.rotation.stationId = :stationId")
    Optional<RotationAssignment> findByParticipationIdAndStationId(
            @Param("participationId") Long participationId,
            @Param("stationId") Long stationId);
}