package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.Etudiant;

import java.util.List;

@Repository
public interface IEtudiantRepository extends JpaRepository<Etudiant, Long> {

    // Explicit JPQL: the entity field is literally named "numero_inscription", so
    // a derived query (findByNumero_inscription) would read the underscore as a
    // property-path separator and look for a nested "numero.inscription". Used by
    // the bulk-import find-or-create — numero_inscription has NO unique constraint
    // (verified: only etudiants_pkey on the table), so this can return >1 row; the
    // importer takes the first match as the canonical directory record.
    @Query("SELECT e FROM Etudiant e WHERE e.numero_inscription = :numero")
    List<Etudiant> findByNumeroInscription(@Param("numero") String numero);
}