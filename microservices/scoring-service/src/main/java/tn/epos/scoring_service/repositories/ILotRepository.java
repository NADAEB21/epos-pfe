package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.Lot;

@Repository
public interface ILotRepository extends JpaRepository<Lot, Long> {
}