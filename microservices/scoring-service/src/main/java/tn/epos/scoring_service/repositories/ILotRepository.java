package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.Lot;

import java.util.List;

@Repository
public interface ILotRepository extends JpaRepository<Lot, Long> {

    // Lots d'un examen (cross-service examen_id) — utilisé par la génération
    // des rotations pour purger l'existant avant régénération.
    List<Lot> findByExamenId(Long examenId);
}