package tn.epos.exam_service.repositories;

import tn.epos.exam_service.entities.Examen;
import tn.epos.exam_service.enums.StatutExamen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExamenRepository extends JpaRepository<Examen, Long> {

    // Filtrer par statut
    List<Examen> findByStatut(StatutExamen statut);

    // Filtrer par matière
    List<Examen> findByMatiereIgnoreCase(String matiere);

    // Vérifier si un examen avec ce nom existe déjà pour cette matière
    boolean existsByNomAndMatiere(String nom, String matiere);

    // Charger examen avec ses stations en une seule requête (évite N+1)
    @Query("SELECT e FROM Examen e LEFT JOIN FETCH e.stations WHERE e.id = :id")
    java.util.Optional<Examen> findByIdWithStations(@Param("id") Long id);
}
