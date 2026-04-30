package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.NotationItem;

import java.util.List;

@Repository
public interface INotationItemRepository extends JpaRepository<NotationItem, Long> {

    // Trouver tous les items d'une notation spécifique
    List<NotationItem> findByNotationId(Long notationId);

}