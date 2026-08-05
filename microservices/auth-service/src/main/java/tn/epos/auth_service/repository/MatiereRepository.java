package tn.epos.auth_service.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.epos.auth_service.entity.Matiere;

import java.util.Optional;

public interface MatiereRepository extends JpaRepository<Matiere, Long> {

    /**
     * #134 — l'unicité du code se compare SANS la casse (« chim_ther » et
     * « CHIM_THER » seraient la même matière en double — même piège que
     * l'unicité d'e-mail, #285).
     */
    Optional<Matiere> findByCodeIgnoreCase(String code);
}
