package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.epos.scoring_service.entities.Reclamation;

import java.util.List;

public interface IReclamationRepository extends JpaRepository<Reclamation, Long> {

    /** The complaint register for one exam, newest first. */
    List<Reclamation> findByExamenIdOrderByCreatedAtDesc(Long examenId);
}
