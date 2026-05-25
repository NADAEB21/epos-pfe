package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.Rotation;

import java.util.List;

@Repository
public interface IRotationRepository extends JpaRepository<Rotation, Long> {

    // Trouver les rotations d'un groupe spécifique
    List<Rotation> findByStudentGroupId(Long groupId);

    // Trouver les rotations associées à une station (cross-service)
    List<Rotation> findByStationId(Long stationId);

    // Trouver toutes les rotations par ordre de passage
    List<Rotation> findAllByOrderByOrdrePassageAsc();
}