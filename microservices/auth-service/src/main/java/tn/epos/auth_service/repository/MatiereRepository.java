package tn.epos.auth_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.epos.auth_service.entity.Matiere;

public interface MatiereRepository extends JpaRepository<Matiere, Long> {
}
