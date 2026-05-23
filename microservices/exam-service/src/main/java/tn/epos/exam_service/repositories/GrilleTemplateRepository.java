package tn.epos.exam_service.repositories;

import tn.epos.exam_service.entities.GrilleTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface GrilleTemplateRepository extends JpaRepository<GrilleTemplate, Long> {

    @Query("SELECT t FROM GrilleTemplate t LEFT JOIN FETCH t.items WHERE t.id = :id")
    Optional<GrilleTemplate> findByIdWithItems(@Param("id") Long id);

    @Query("SELECT t FROM GrilleTemplate t LEFT JOIN FETCH t.items")
    List<GrilleTemplate> findAllWithItems();

    boolean existsByNom(String nom);
}
