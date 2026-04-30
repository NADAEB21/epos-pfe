package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.ExamenParticipation;

@Repository
public interface IExamenParticipationRepository extends JpaRepository<ExamenParticipation, Long> {

}
