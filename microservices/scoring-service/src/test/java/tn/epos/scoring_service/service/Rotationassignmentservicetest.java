package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.scoring_service.config.EvaluateurScopeChecker;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationAssignment;
import tn.epos.scoring_service.entities.RotationStatus;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;
import tn.epos.scoring_service.repositories.IRotationAssignmentRepository;
import tn.epos.scoring_service.repositories.IRotationRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RotationAssignmentService - Tests unitaires")
class RotationAssignmentServiceTest {

    @Mock
    private IRotationAssignmentRepository repository;

    @Mock
    private IRotationRepository rotationRepository;

    @Mock
    private IExamenParticipationRepository participationRepository;

    @Mock
    private EvaluateurScopeChecker scopeChecker;

    @InjectMocks
    private RotationAssignmentService service;
    private RotationAssignment assignment;
    private Rotation rotation;
    private ExamenParticipation participation;

    @BeforeEach
    void setUp() {
        // Appelant non contraint par défaut ; filtrage de périmètre (#91)
        // couvert par EvaluateurScopeCheckerTest. lenient() : findById/update/
        // confirmerPresence ne consultent pas le checker.
        // #274 — filtre de LISTE : `peutLireHorsPerimetre`, pas l'écriture.
        lenient().when(scopeChecker.peutLireHorsPerimetre()).thenReturn(true);

        rotation = new Rotation();
        rotation.setId(1L);
        rotation.setStatut(RotationStatus.EN_ATTENTE);

        participation = new ExamenParticipation();
        participation.setId(1L);
        participation.setExamen_id(10L);

        assignment = new RotationAssignment();
        assignment.setId(1L);
        assignment.setPresenceConfirmee(false);
        assignment.setTempsAdditionnel(0);
        assignment.setRotation(rotation);
        assignment.setParticipation(participation);
    }

    @Nested
    @DisplayName("findAll()")
    class FindAll {

        @Test
        @DisplayName("Doit retourner tous les assignments")
        void findAll_devraitRetournerListe() {
            when(repository.findAll()).thenReturn(List.of(assignment));

            List<RotationAssignment> result = service.findAll();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPresenceConfirmee()).isFalse();
            verify(repository, times(1)).findAll();
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucun assignment")
        void findAll_devraitRetournerListeVide() {
            when(repository.findAll()).thenReturn(List.of());

            List<RotationAssignment> result = service.findAll();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findById()")
    class FindById {

        @Test
        @DisplayName("Doit retourner l'assignment si trouvé")
        void findById_devraitRetournerAssignment() {
            when(repository.findById(1L)).thenReturn(Optional.of(assignment));

            Optional<RotationAssignment> result = service.findById(1L);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(1L);
            assertThat(result.get().getRotation().getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Doit retourner Optional vide si introuvable")
        void findById_devraitRetournerVideSiIntrouvable() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            Optional<RotationAssignment> result = service.findById(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByRotation()")
    class FindByRotation {

        @Test
        @DisplayName("Doit retourner les assignments d'une rotation donnée")
        void findByRotation_devraitRetournerListe() {
            when(repository.findByRotationId(1L)).thenReturn(List.of(assignment));

            List<RotationAssignment> result = service.findByRotation(1L);

            assertThat(result).hasSize(1);
            verify(repository, times(1)).findByRotationId(1L);
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucun assignment pour cette rotation")
        void findByRotation_devraitRetournerListeVide() {
            when(repository.findByRotationId(99L)).thenReturn(List.of());

            List<RotationAssignment> result = service.findByRotation(99L);

            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // save() / update() / delete() / confirmerPresence() — SUPPRIMES (#218).
    // Leurs tests partent avec eux. La presence au grain du LOT est testee par
    // LotAssignmentServiceTest (markPresence), seul chemin conserve.
    // =========================================================================
}
