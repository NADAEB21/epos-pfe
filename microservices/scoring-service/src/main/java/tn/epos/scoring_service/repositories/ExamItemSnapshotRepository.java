package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.ExamItemSnapshot;

import java.util.List;
import java.util.Optional;

/** ADR-0015 — write-once snapshot of a grille's notable (leaf) items. */
@Repository
public interface ExamItemSnapshotRepository extends JpaRepository<ExamItemSnapshot, Long> {

    /** The notable-item set for a grille. Empty ⇒ not yet materialised (NOT "no items"). */
    List<ExamItemSnapshot> findByGrilleId(Long grilleId);

    Optional<ExamItemSnapshot> findByItemId(Long itemId);

    /** #361 — validation des cibles d'un barème de délibération (ADR-0030 D2). */
    List<ExamItemSnapshot> findByExamenId(Long examenId);

    /** For #183 "dé-lancer" invalidation: a relaunch must re-copy the definition. */
    void deleteByExamenId(Long examenId);
}
