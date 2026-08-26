package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.BaremeDeliberation;

import java.util.List;
import java.util.Optional;

/**
 * ADR-0030 — en-têtes des barèmes de délibération. Insert-only : aucune méthode
 * de mise à jour/suppression n'est exposée volontairement (les versions sont
 * immuables — corriger, c'est écrire une nouvelle version).
 */
@Repository
public interface IBaremeDeliberationRepository extends JpaRepository<BaremeDeliberation, Long> {

    /** Historique complet, version la plus récente d'abord (D3 : la dernière fait foi). */
    List<BaremeDeliberation> findByExamenIdOrderByVersionDesc(Long examenId);

    /** La version COURANTE d'un examen — celle que le recalcul de lecture applique. */
    Optional<BaremeDeliberation> findTopByExamenIdOrderByVersionDesc(Long examenId);
}
