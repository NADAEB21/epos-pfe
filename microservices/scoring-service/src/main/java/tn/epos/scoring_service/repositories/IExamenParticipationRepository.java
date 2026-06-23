package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.ExamenParticipation;

import java.util.List;
import java.util.Optional;

@Repository
public interface IExamenParticipationRepository extends JpaRepository<ExamenParticipation, Long> {
    /**
     * Récupère les participations d'un lot (= les étudiants du lot).
     * Utilisé pour construire la liste des étudiants dans LotDetailResponse.
     */
     List<ExamenParticipation> findByLotId(Long lotId);


    // Explicit JPQL: the field is literally named "examen_id", so a derived
    // query (findByExamen_id) would read the underscore as a property-path
    // separator and look for a nested "examen.id" that does not exist.
    @Query("SELECT p FROM ExamenParticipation p WHERE p.examen_id = :examenId")
    List<ExamenParticipation> findByExamenId(@Param("examenId") Long examenId);


    /**
     * Retrouve la participation d'un étudiant pour un examen donné.
     * NOTE : stationId est utilisé comme examen_id ici car dans le contexte
     * du mock Flutter, l'id de la session = id de la station = id de l'examen.
     * À ajuster selon le vrai mapping examen_id ↔ station_id de votre données.
    */
     Optional<ExamenParticipation> findByEtudiantIdAndLotExamenId(Long etudiantId, Long examenId);

    /**
     * Retrouve la participation d'un étudiant pour une station donnée.
     *
     * La navigation JPQL suit le chemin :
     *   ExamenParticipation → lot → groups (StudentGroup) → rotations (Rotation) → stationId
     *
     * Utilisé par saisirNotation() et validerEtudiant() comme point d'entrée
     * pour retrouver la chaîne : participation → assignment → notation → items.
     *
     * DISTINCT est nécessaire car un étudiant peut avoir plusieurs rotations
     * (une par lot) mais une seule participation par station.
     */
    @Query("SELECT DISTINCT p FROM ExamenParticipation p " +
            "JOIN p.lot l " +
            "JOIN l.groups sg " +
            "JOIN sg.rotations r " +
            "WHERE p.etudiant.id = :etudiantId AND r.stationId = :stationId")
    Optional<ExamenParticipation> findByEtudiantIdAndStationId(
            @Param("etudiantId") Long etudiantId,
            @Param("stationId") Long stationId);

    // Pre-check for the bulk importer so an already-enrolled student is reported
    // as ALREADY_ENROLLED instead of tripping the uq_participation_examen_etudiant
    // unique constraint (examen_id, etudiant_id). examen_id is a flat column (no
    // entity path) but etudiant.id IS a real association path.
    @Query("SELECT COUNT(p) > 0 FROM ExamenParticipation p "
            + "WHERE p.examen_id = :examenId AND p.etudiant.id = :etudiantId")
    boolean existsByExamenAndEtudiant(@Param("examenId") Long examenId,
                                      @Param("etudiantId") Long etudiantId);
}
