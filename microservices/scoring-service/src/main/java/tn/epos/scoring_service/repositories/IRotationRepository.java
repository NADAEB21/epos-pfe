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

    /**
     * Nombre de rotations déjà générées pour un lot (issue #188).
     *
     * <p>Le front doit savoir, AU CHARGEMENT, si un lot possède déjà un planning : sans
     * cela il affiche « Générer » sur un lot déjà généré et la régénération — destructrice —
     * part sans confirmation. L'état de session ne suffit pas : il est vide après un reload.
     */
    long countByStudentGroupLotId(Long lotId);
}