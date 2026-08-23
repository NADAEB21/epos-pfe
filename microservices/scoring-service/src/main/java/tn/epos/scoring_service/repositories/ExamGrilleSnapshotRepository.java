package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.epos.scoring_service.entities.ExamGrilleSnapshot;

import java.util.List;
import java.util.Optional;

public interface ExamGrilleSnapshotRepository extends JpaRepository<ExamGrilleSnapshot, Long> {
    Optional<ExamGrilleSnapshot> findByStationId(Long stationId);
    // #355 — l'écran de délibération lit les barèmes de TOUT l'examen en un appel
    // (le fan-out par station multipliait les requêtes et les modes de panne).
    List<ExamGrilleSnapshot> findByExamenId(Long examenId);
    void deleteByExamenId(Long examenId);
}
