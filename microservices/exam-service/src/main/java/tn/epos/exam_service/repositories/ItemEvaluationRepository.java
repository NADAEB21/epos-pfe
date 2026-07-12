package tn.epos.exam_service.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.epos.exam_service.entities.ItemEvaluation;
import tn.epos.exam_service.enums.TypeItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ItemEvaluationRepository extends JpaRepository<ItemEvaluation, Long> {

    // Tous les items d'une grille, triés par ordre
    List<ItemEvaluation> findByGrilleIdOrderByOrdreAsc(Long grilleId);          // usage interne
    Page<ItemEvaluation> findByGrilleIdOrderByOrdreAsc(Long grilleId, Pageable pageable); // pagination

    // Items d'une grille filtrés par type (BINAIRE ou NUMERIQUE)
    List<ItemEvaluation> findByGrilleIdAndTypeOrderByOrdreAsc(Long grilleId, TypeItem type);

    List<ItemEvaluation> findByGrilleIdAndParentIsNullOrderByOrdreAsc(Long grilleId);
    Page<ItemEvaluation> findByGrilleIdAndParentIsNullOrderByOrdreAsc(Long grilleId, Pageable pageable);
    List<ItemEvaluation> findByParentIdOrderByOrdreAsc(Long parentId);

    // Calcul de la somme des pondérations d'une grille (requête agrégée)
    @Query("SELECT COALESCE(SUM(i.ponderation), 0.0) FROM ItemEvaluation i WHERE i.grille.id = :grilleId")
    Double sumPonderationsByGrilleId(@Param("grilleId") Long grilleId);

    // Nombre d'items dans une grille (pour calculer l'ordre automatique)
    long countByGrilleId(Long grilleId);

    // Supprimer tous les items d'une grille (utilisé en cas de reconfiguration)
    @Modifying
    @Query("DELETE FROM ItemEvaluation i WHERE i.grille.id = :grilleId")
    void deleteAllByGrilleId(@Param("grilleId") Long grilleId);
}
