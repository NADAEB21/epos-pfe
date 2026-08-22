package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.Etudiant;

import java.util.List;

@Repository
public interface IEtudiantRepository extends JpaRepository<Etudiant, Long> {

    // #351 — UPPER(TRIM(...)) des DEUX côtés : couvre à la fois les lignes
    // historiques non encore normalisées (avant migration) et un appelant qui
    // passerait un numéro brut sans être passé par EtudiantService.
    @Query("SELECT e FROM Etudiant e WHERE UPPER(TRIM(e.numero_inscription)) = UPPER(TRIM(:numero))")
    List<Etudiant> findByNumeroInscription(@Param("numero") String numero);
}