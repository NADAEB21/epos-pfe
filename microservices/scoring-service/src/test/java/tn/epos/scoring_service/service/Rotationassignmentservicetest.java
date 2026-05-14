package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationAssignment;
import tn.epos.scoring_service.entities.RotationStatus;
import tn.epos.scoring_service.repositories.IRotationAssignmentRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RotationAssignmentService - Tests unitaires")
class RotationAssignmentServiceTest {

    @Mock
    private IRotationAssignmentRepository repository;

    @InjectMocks
    private RotationAssignmentService service;
    private RotationAssignment assignment;
    private Rotation rotation;
    private ExamenParticipation participation;

    @BeforeEach
    void setUp() {
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

    @Nested
    @DisplayName("save()")
    class Save {

        @Test
        @DisplayName("Doit sauvegarder et retourner l'assignment")
        void save_devraitSauvegarder() {
            when(repository.save(any(RotationAssignment.class))).thenReturn(assignment);

            RotationAssignment result = service.save(assignment);

            assertThat(result).isNotNull();
            assertThat(result.getParticipation().getExamen_id()).isEqualTo(10L);
            verify(repository, times(1)).save(any(RotationAssignment.class));
        }
    }

    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("Doit appeler deleteById avec le bon ID")
        void delete_devraitAppelerDeleteById() {
            doNothing().when(repository).deleteById(1L);

            service.delete(1L);

            verify(repository, times(1)).deleteById(1L);
        }
    }

    @Nested
    @DisplayName("update()")
    class Update {

        @Test
        @DisplayName("Doit mettre à jour les champs de l'assignment si trouvé")
        void update_devraitMettreAJour() {
            Rotation nouvelleRotation = new Rotation();
            nouvelleRotation.setId(2L);

            ExamenParticipation nouvelleParticipation = new ExamenParticipation();
            nouvelleParticipation.setId(2L);

            RotationAssignment details = new RotationAssignment();
            details.setPresenceConfirmee(true);
            details.setTempsAdditionnel(10);
            details.setRotation(nouvelleRotation);
            details.setParticipation(nouvelleParticipation);

            when(repository.findById(1L)).thenReturn(Optional.of(assignment));
            when(repository.save(any(RotationAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

            RotationAssignment result = service.update(1L, details);

            assertThat(result.getPresenceConfirmee()).isTrue();
            assertThat(result.getTempsAdditionnel()).isEqualTo(10);
            assertThat(result.getRotation().getId()).isEqualTo(2L);
            assertThat(result.getParticipation().getId()).isEqualTo(2L);
            verify(repository).save(any(RotationAssignment.class));
        }

        @Test
        @DisplayName("Doit lever RuntimeException si assignment introuvable")
        void update_devraitLeverExceptionSiIntrouvable() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(99L, new RotationAssignment()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("99");
        }
    }

    @Nested
    @DisplayName("confirmerPresence()")
    class ConfirmerPresence {

        @Test
        @DisplayName("Doit confirmer la présence à true et sauvegarder")
        void confirmerPresence_devraitSetPresenceTrue() {
            when(repository.findById(1L)).thenReturn(Optional.of(assignment));
            when(repository.save(any(RotationAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

            RotationAssignment result = service.confirmerPresence(1L, true);

            assertThat(result.getPresenceConfirmee()).isTrue();
            verify(repository).save(any(RotationAssignment.class));
        }

        @Test
        @DisplayName("Doit confirmer la présence à false et sauvegarder")
        void confirmerPresence_devraitSetPresenceFalse() {
            assignment.setPresenceConfirmee(true);
            when(repository.findById(1L)).thenReturn(Optional.of(assignment));
            when(repository.save(any(RotationAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

            RotationAssignment result = service.confirmerPresence(1L, false);

            assertThat(result.getPresenceConfirmee()).isFalse();
            verify(repository).save(any(RotationAssignment.class));
        }

        @Test
        @DisplayName("Doit lever RuntimeException si assignment introuvable")
        void confirmerPresence_devraitLeverExceptionSiIntrouvable() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirmerPresence(99L, true))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("99");
        }
    }
}
