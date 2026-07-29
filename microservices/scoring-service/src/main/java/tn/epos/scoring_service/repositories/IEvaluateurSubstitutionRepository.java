package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.EvaluateurSubstitution;

import java.util.List;

/** ADR-0017 — historique des suppléances, pour pouvoir les expliquer après coup. */
@Repository
public interface IEvaluateurSubstitutionRepository extends JpaRepository<EvaluateurSubstitution, Long> {

    List<EvaluateurSubstitution> findByLotIdOrderBySurvenuADesc(Long lotId);
}
