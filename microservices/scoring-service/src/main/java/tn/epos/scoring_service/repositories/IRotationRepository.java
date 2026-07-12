package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.Rotation;

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
}