package tn.epos.exam_service.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.epos.exam_service.entities.Station;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StationRepository extends JpaRepository<Station, Long> {

    // Récupérer toutes les stations d'un examen, triées par ordre
    Page<Station> findByExamenIdOrderByOrdreAsc(Long examenId, Pageable pageable); // pour la pagination
    List<Station> findByExamenIdOrderByOrdreAsc(Long examenId); // pour la réordination interne

    // Charger une station avec sa grille (évite N+1)
    @Query("SELECT s FROM Station s LEFT JOIN FETCH s.grille WHERE s.id = :id")
    Optional<Station> findByIdWithGrille(@Param("id") Long id);

    // Vérifier si une station avec ce nom existe dans un examen
    boolean existsByNomAndExamenId(String nom, Long examenId);

    // Nombre de stations dans un examen (pour calculer l'ordre automatique)
    long countByExamenId(Long examenId);
}
