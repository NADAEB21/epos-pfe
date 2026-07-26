package tn.epos.exam_service.repositories;

import tn.epos.exam_service.entities.Examen;
import tn.epos.exam_service.enums.StatutExamen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Set;

@Repository
public interface ExamenRepository extends JpaRepository<Examen, Long> {

    // Filtrer par statut
    Page<Examen> findByStatut(StatutExamen statut, Pageable pageable);

    // Filtrer par matière (id)
    Page<Examen> findByMatiereId(Long matiereId, Pageable pageable);

    // Filtrer par périmètre matière (#95 — list endpoint scope filter)
    Page<Examen> findByMatiereIdIn(Set<Long> matiereIds, Pageable pageable);

    // Filtrer par périmètre matière + statut (#95)
    Page<Examen> findByMatiereIdInAndStatut(Set<Long> matiereIds, StatutExamen statut, Pageable pageable);

    // Vérifier si un examen avec ce nom existe déjà pour cette matière
    boolean existsByNomAndMatiereId(String nom, Long matiereId);

    // Charger examen avec ses stations en une seule requête (évite N+1)
    @Query("SELECT e FROM Examen e LEFT JOIN FETCH e.stations WHERE e.id = :id")
    java.util.Optional<Examen> findByIdWithStations(@Param("id") Long id);

    // #265 — tous les examens d'un statut, non paginé (garde de lancement :
    // l'ensemble des examens EN_COURS d'une faculté est petit par nature)
    java.util.List<Examen> findAllByStatut(StatutExamen statut);
}
