package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.NotationAdjustment;

import java.util.List;

@Repository
public interface INotationAdjustmentRepository extends JpaRepository<NotationAdjustment, Long> {

    /** Adjustment history for one notation, most recent first. */
    List<NotationAdjustment> findByNotationIdOrderByAdjustedAtDesc(Long notationId);
}
