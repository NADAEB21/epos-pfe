package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.BaremeDeliberationOperation;

import java.util.Collection;
import java.util.List;

/** ADR-0030 — opérations filles d'un barème de délibération (insert-only). */
@Repository
public interface IBaremeDeliberationOperationRepository
        extends JpaRepository<BaremeDeliberationOperation, Long> {

    List<BaremeDeliberationOperation> findByBaremeId(Long baremeId);

    /** Chargement groupé pour l'historique (une requête, pas une par version). */
    List<BaremeDeliberationOperation> findByBaremeIdIn(Collection<Long> baremeIds);
}
