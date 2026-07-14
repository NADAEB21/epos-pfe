package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.Notation;

import java.util.List;
import java.util.Optional;

@Repository
public interface INotationRepository extends JpaRepository<Notation, Long> {

    // Trouver la notation associée à une assignment spécifique
    Optional<Notation> findByAssignmentId(Long assignmentId);

    // Trouver les notations d'une station donnée (cross-service)
    List<Notation> findByStationId(Long stationId);

    // Trouver les notations d'une grille donnée (cross-service)
    List<Notation> findByGrilleId(Long grilleId);

    // Toutes les notations d'un examen, jointes jusqu'à l'étudiant (issue #90).
    // La chaîne notation -> assignment -> participation -> etudiant est traversée
    // en JOIN FETCH pour bâtir l'agrégat par étudiant sans N+1 sur les lazy.
    // Le filtre porte sur le FK logique ExamenParticipation.examen_id (snake_case
    // côté Java — référencé tel quel en JPQL, pas une derived query).
    @Query("SELECT n FROM Notation n " +
           "JOIN FETCH n.assignment a " +
           "JOIN FETCH a.participation p " +
           "JOIN FETCH p.etudiant e " +
           "WHERE p.examen_id = :examenId")
    List<Notation> findByExamenIdWithGraph(@Param("examenId") Long examenId);

    /**
     * Compte les notations qu'une regénération du lot DÉTRUIRAIT (issue #188).
     *
     * <p>{@code RotationGenerationService.wipeLotGroups(lotId)} supprime les
     * {@code StudentGroup} du lot, et toute la chaîne dessous est en CASCADE — en JPA
     * comme au niveau FK PostgreSQL ({@code confdeltype='c'}) :
     * <pre>
     *   student_group ─c→ rotation ─c→ rotation_assignment ─c→ notations ─c→ notation_items
     *                                                                 └─c→ notation_adjustments
     * </pre>
     * Une régénération efface donc les notes déjà saisies, y compris les notations
     * VERROUILLÉES (que l'ADR-0013 déclare immuables) et leur piste d'audit.
     *
     * <p>On compte par les DEUX chemins qui relient une notation à un lot — celui de la
     * cascade ({@code assignment → rotation → studentGroup → lot}) et le lien métier
     * ({@code assignment → participation → lot}) — pour ne jamais sous-estimer ce qui
     * serait perdu. En cas de doute : on refuse (fail closed).
     */
    @Query("SELECT COUNT(n) FROM Notation n " +
           "WHERE n.assignment.rotation.studentGroup.lot.id = :lotId " +
           "   OR n.assignment.participation.lot.id = :lotId")
    long countNotationsAtRiskForLot(@Param("lotId") Long lotId);
}