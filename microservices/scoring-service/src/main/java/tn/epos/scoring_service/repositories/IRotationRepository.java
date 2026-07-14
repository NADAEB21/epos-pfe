package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.Rotation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface IRotationRepository extends JpaRepository<Rotation, Long> {

    // Trouver les rotations d'un groupe spécifique
    List<Rotation> findByStudentGroupId(Long groupId);

    // Trouver les rotations associées à une station (cross-service)
    List<Rotation> findByStationId(Long stationId);

    // Trouver toutes les rotations par ordre de passage
    List<Rotation> findAllByOrderByOrdrePassageAsc();

     /**
     * Récupère toutes les rotations d'un évaluateur.
     * Utilisé pour construire le planning du jour.
     */
     List<Rotation> findByEvaluateurId(Long evaluateurId);

    /**
     * Retourne la rotation la plus récente pour un évaluateur et une station.
     * findFirst évite NonUniqueResultException si des doublons existent.
     */
    Optional<Rotation> findFirstByEvaluateurIdAndStationIdOrderByIdDesc(
            Long evaluateurId, Long stationId);

     /**
      * Trouve la rotation d'un évaluateur pour une station donnée.
      * Utilisé lors de la création automatique d'un RotationAssignment.
      */
     Optional<Rotation> findByEvaluateurIdAndStationId(Long evaluateurId, Long stationId);

    // #189 — étape 1 : quels examens cet évaluateur a-t-il des rotations dedans ?
    // Requête légère (juste les IDs), qui sert à interroger exam-service AVANT de
    // charger les rotations complètes.
    @Query("SELECT DISTINCT r.studentGroup.lot.examenId FROM Rotation r " +
            "WHERE r.evaluateurId = :evaluateurId " +
            "AND r.studentGroup IS NOT NULL AND r.studentGroup.lot IS NOT NULL")
    List<Long> findDistinctExamenIdsByEvaluateurId(@Param("evaluateurId") Long evaluateurId);

    // #189 — étape 2 : filtre au niveau requête (pas en Java stream), pour rester
    // bon marché quand le volume de rotations grandit.
    List<Rotation> findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(
            Long evaluateurId, Collection<Long> examenIds);
}