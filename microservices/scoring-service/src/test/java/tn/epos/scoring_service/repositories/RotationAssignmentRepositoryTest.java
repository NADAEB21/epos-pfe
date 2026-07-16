package tn.epos.scoring_service.repositories;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import tn.epos.scoring_service.entities.*;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #203 — cardinalité participation ↔ assignment (1-N, PAS 1-1).
 *
 * <p>Dans un examen OSCE une même participation (un étudiant dans un examen) a UN
 * {@link RotationAssignment} PAR station du circuit. L'ancien
 * {@code findByParticipationId} renvoyait un {@code Optional} et jetait
 * {@code NonUniqueResultException} (500) dès la 2ᵉ station — c'est-à-dire le cas
 * normal. Ce test pose une participation avec DEUX assignments (stations 9 et 10)
 * et vérifie que le lookup scopé {@code findByParticipationIdAndStationId} renvoie
 * le bon passage unique par station, sans jamais échouer sur la multiplicité.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("IRotationAssignmentRepository — #203 lookup scopé (participation, station)")
class RotationAssignmentRepositoryTest {

    @Autowired private TestEntityManager em;
    @Autowired private IRotationAssignmentRepository repository;

    private static final Long STATION_A = 9L;
    private static final Long STATION_B = 10L;

    @Test
    @DisplayName("Une participation sur 2 stations : chaque station résout SON assignment, aucun crash")
    void findByParticipationIdAndStationId_multiStation() {
        // -- Étudiant + participation (une seule, comme en base : per étudiant/examen) --
        Etudiant etudiant = new Etudiant();
        etudiant.setNom("Test");
        etudiant.setPrenom("Multi");
        em.persist(etudiant);

        ExamenParticipation participation = new ExamenParticipation();
        participation.setEtudiant(etudiant);
        participation.setExamen_id(99L);
        participation.setEst_present(true);
        em.persist(participation);

        // -- Deux rotations, deux stations distinctes du même circuit --
        Rotation rotA = new Rotation();
        rotA.setStationId(STATION_A);
        rotA.setStatut(RotationStatus.EN_ATTENTE);
        em.persist(rotA);

        Rotation rotB = new Rotation();
        rotB.setStationId(STATION_B);
        rotB.setStatut(RotationStatus.EN_ATTENTE);
        em.persist(rotB);

        // -- La MÊME participation reçoit un assignment sur CHAQUE station (1-N) --
        RotationAssignment assignA = new RotationAssignment();
        assignA.setParticipation(participation);
        assignA.setRotation(rotA);
        em.persist(assignA);

        RotationAssignment assignB = new RotationAssignment();
        assignB.setParticipation(participation);
        assignB.setRotation(rotB);
        em.persist(assignB);

        em.flush();
        em.clear();

        Long pId = participation.getId();

        // Pré-condition #203 : cette participation a bien PLUSIEURS assignments —
        // exactement la situation où l'ancien findByParticipationId jetait 500.
        assertThat(repository.findAll())
                .filteredOn(a -> a.getParticipation().getId().equals(pId))
                .hasSize(2);

        // Chaque station résout son passage unique, sans NonUniqueResultException.
        Optional<RotationAssignment> onA = repository.findByParticipationIdAndStationId(pId, STATION_A);
        assertThat(onA).isPresent();
        assertThat(onA.get().getRotation().getStationId()).isEqualTo(STATION_A);
        assertThat(onA.get().getId()).isEqualTo(assignA.getId());

        Optional<RotationAssignment> onB = repository.findByParticipationIdAndStationId(pId, STATION_B);
        assertThat(onB).isPresent();
        assertThat(onB.get().getRotation().getStationId()).isEqualTo(STATION_B);
        assertThat(onB.get().getId()).isEqualTo(assignB.getId());
    }

    @Test
    @DisplayName("Station sans assignment pour cette participation → Optional vide (pas d'erreur)")
    void findByParticipationIdAndStationId_absent() {
        Etudiant etudiant = new Etudiant();
        etudiant.setNom("Solo");
        etudiant.setPrenom("Une");
        em.persist(etudiant);

        ExamenParticipation participation = new ExamenParticipation();
        participation.setEtudiant(etudiant);
        participation.setExamen_id(99L);
        em.persist(participation);

        Rotation rot = new Rotation();
        rot.setStationId(STATION_A);
        rot.setStatut(RotationStatus.EN_ATTENTE);
        em.persist(rot);

        RotationAssignment assign = new RotationAssignment();
        assign.setParticipation(participation);
        assign.setRotation(rot);
        em.persist(assign);
        em.flush();
        em.clear();

        assertThat(repository.findByParticipationIdAndStationId(participation.getId(), 999L))
                .isEmpty();
    }
}
