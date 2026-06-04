package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.ExamenParticipation;

import java.util.List;

@Repository
public interface IExamenParticipationRepository extends JpaRepository<ExamenParticipation, Long> {

    // Explicit JPQL: the field is literally named "examen_id", so a derived
    // query (findByExamen_id) would read the underscore as a property-path
    // separator and look for a nested "examen.id" that does not exist.
    @Query("SELECT p FROM ExamenParticipation p WHERE p.examen_id = :examenId")
    List<ExamenParticipation> findByExamenId(@Param("examenId") Long examenId);
}
