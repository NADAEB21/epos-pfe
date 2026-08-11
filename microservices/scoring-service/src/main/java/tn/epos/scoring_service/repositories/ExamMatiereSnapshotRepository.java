package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.ExamMatiereSnapshot;

import java.util.Optional;

/** ADR-0015 / #274 — matière d'un examen, figée une fois pour l'autorisation locale. */
@Repository
public interface ExamMatiereSnapshotRepository extends JpaRepository<ExamMatiereSnapshot, Long> {

    Optional<ExamMatiereSnapshot> findByExamenId(Long examenId);

    /**
     * Invalidation par examen — symétrie avec les deux autres instantanés (#183 « dé-lancer »).
     * Sans appelant aujourd'hui, comme {@code ExamDefinitionSnapshotService.invalidateExam} :
     * il n'existe aucun chemin exam → scoring (ADR-0020). Présent pour que l'invalidation, le
     * jour où elle sera câblée, ne rate pas une table.
     */
    void deleteByExamenId(Long examenId);
}
