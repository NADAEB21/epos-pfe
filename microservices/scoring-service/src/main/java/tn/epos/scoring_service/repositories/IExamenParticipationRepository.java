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

    // The lot association IS a real entity path (p.lot.id), unlike the flat
    // examen_id column above, so a derived query resolves cleanly. Used by the
    // two-phase lot workflow: per-lot presence marking and per-lot generation.
    List<ExamenParticipation> findByLotId(Long lotId);

    // Pre-check for the bulk importer so an already-enrolled student is reported
    // as ALREADY_ENROLLED instead of tripping the uq_participation_examen_etudiant
    // unique constraint (examen_id, etudiant_id). examen_id is a flat column (no
    // entity path) but etudiant.id IS a real association path.
    @Query("SELECT COUNT(p) > 0 FROM ExamenParticipation p "
            + "WHERE p.examen_id = :examenId AND p.etudiant.id = :etudiantId")
    boolean existsByExamenAndEtudiant(@Param("examenId") Long examenId,
                                      @Param("etudiantId") Long etudiantId);
}
