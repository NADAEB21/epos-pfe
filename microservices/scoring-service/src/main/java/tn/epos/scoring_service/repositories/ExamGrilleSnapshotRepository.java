package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.epos.scoring_service.entities.ExamGrilleSnapshot;

import java.util.Optional;

public interface ExamGrilleSnapshotRepository extends JpaRepository<ExamGrilleSnapshot, Long> {
    Optional<ExamGrilleSnapshot> findByStationId(Long stationId);
    void deleteByExamenId(Long examenId);
}
