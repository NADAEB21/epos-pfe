package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.epos.scoring_service.entities.Reclamation;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IReclamationRepository extends JpaRepository<Reclamation, Long> {

    /** The complaint register for one exam, newest first. */
    List<Reclamation> findByExamenIdOrderByCreatedAtDesc(Long examenId);
}
