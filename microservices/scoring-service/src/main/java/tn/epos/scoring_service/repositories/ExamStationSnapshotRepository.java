package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.ExamStationSnapshot;

import java.util.List;
import java.util.Optional;

/** ADR-0015 — write-once station-name snapshot. */
@Repository
public interface ExamStationSnapshotRepository extends JpaRepository<ExamStationSnapshot, Long> {

    Optional<ExamStationSnapshot> findByStationId(Long stationId);

    /** For #183 "dé-lancer" invalidation: a relaunch must re-copy the definition. */
    List<ExamStationSnapshot> findByExamenId(Long examenId);

    void deleteByExamenId(Long examenId);
}
