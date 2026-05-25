package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.scoring_service.entities.*;
import tn.epos.scoring_service.repositories.IRotationRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RotationService - Tests unitaires")
class RotationServiceTest {

    @Mock
    private IRotationRepository rotationRepository;

    @InjectMocks
    private RotationService rotationService;
    private Rotation rotation;
    private StudentGroup studentGroup;

    @BeforeEach
    void setUp() {
        Lot lot = new Lot();
        lot.setId(1L);
        lot.setStatut(LotStatus.EN_ATTENTE);

        studentGroup = new StudentGroup();
        studentGroup.setId(1L);
        studentGroup.setNumeroGroupe(1);
        studentGroup.setLot(lot);

        rotation = new Rotation();
        rotation.setId(1L);
        rotation.setEvaluateurId(3L);
        rotation.setStationId(7L);
        rotation.setOrdrePassage(1);
        rotation.setDebutCreneau(LocalDateTime.of(2024, 6, 15, 9, 0));
        rotation.setStatut(RotationStatus.EN_ATTENTE);
        rotation.setStudentGroup(studentGroup);
    }

    @Nested
    @DisplayName("findAll()")
    class FindAll {

        @Test
        @DisplayName("Doit retourner toutes les rotations")
        void findAll_devraitRetournerListe() {
            when(rotationRepository.findAll()).thenReturn(List.of(rotation));

            List<Rotation> result = rotationService.findAll();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getOrdrePassage()).isEqualTo(1);
            assertThat(result.get(0).getStatut()).isEqualTo(RotationStatus.EN_ATTENTE);
            verify(rotationRepository, times(1)).findAll();
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucune rotation")
        void findAll_devraitRetournerListeVide() {
            when(rotationRepository.findAll()).thenReturn(List.of());

            List<Rotation> result = rotationService.findAll();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findById()")
    class FindById {

        @Test
        @DisplayName("Doit retourner la rotation si trouvée")
        void findById_devraitRetournerRotation() {
            when(rotationRepository.findById(1L)).thenReturn(Optional.of(rotation));

            Optional<Rotation> result = rotationService.findById(1L);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(1L);
            assertThat(result.get().getEvaluateurId()).isEqualTo(3L);
        }

        @Test
        @DisplayName("Doit retourner Optional vide si introuvable")
        void findById_devraitRetournerVideSiIntrouvable() {
            when(rotationRepository.findById(99L)).thenReturn(Optional.empty());

            Optional<Rotation> result = rotationService.findById(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByGroup()")
    class FindByGroup {

        @Test
        @DisplayName("Doit retourner les rotations d'un groupe donné")
        void findByGroup_devraitRetournerRotationsDuGroupe() {
            when(rotationRepository.findByStudentGroupId(1L)).thenReturn(List.of(rotation));

            List<Rotation> result = rotationService.findByGroup(1L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStudentGroup().getId()).isEqualTo(1L);
            verify(rotationRepository, times(1)).findByStudentGroupId(1L);
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucune rotation pour ce groupe")
        void findByGroup_devraitRetournerListeVide() {
            when(rotationRepository.findByStudentGroupId(99L)).thenReturn(List.of());

            List<Rotation> result = rotationService.findByGroup(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByStation()")
    class FindByStation {

        @Test
        @DisplayName("Doit retourner les rotations d'une station donnée")
        void findByStation_devraitRetournerRotationsDeLaStation() {
            when(rotationRepository.findByStationId(7L)).thenReturn(List.of(rotation));

            List<Rotation> result = rotationService.findByStation(7L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStationId()).isEqualTo(7L);
            verify(rotationRepository, times(1)).findByStationId(7L);
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucune rotation pour cette station")
        void findByStation_devraitRetournerListeVide() {
            when(rotationRepository.findByStationId(99L)).thenReturn(List.of());

            List<Rotation> result = rotationService.findByStation(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("save()")
    class Save {

        @Test
        @DisplayName("Doit sauvegarder et retourner la rotation")
        void save_devraitSauvegarder() {
            when(rotationRepository.save(any(Rotation.class))).thenReturn(rotation);

            Rotation result = rotationService.save(rotation);

            assertThat(result).isNotNull();
            assertThat(result.getStatut()).isEqualTo(RotationStatus.EN_ATTENTE);
            assertThat(result.getStudentGroup().getNumeroGroupe()).isEqualTo(1);
            verify(rotationRepository, times(1)).save(any(Rotation.class));
        }
    }

    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("Doit appeler deleteById avec le bon ID")
        void delete_devraitAppelerDeleteById() {
            doNothing().when(rotationRepository).deleteById(1L);

            rotationService.delete(1L);

            verify(rotationRepository, times(1)).deleteById(1L);
        }
    }

    @Nested
    @DisplayName("update()")
    class Update {

        @Test
        @DisplayName("Doit mettre à jour tous les champs de la rotation si trouvée")
        void update_devraitMettreAJourRotation() {
            StudentGroup nouveauGroupe = new StudentGroup();
            nouveauGroupe.setId(2L);
            nouveauGroupe.setNumeroGroupe(2);

            Rotation details = new Rotation();
            details.setOrdrePassage(2);
            details.setDebutCreneau(LocalDateTime.of(2024, 6, 15, 10, 0));
            details.setStatut(RotationStatus.EN_COURS);
            details.setEvaluateurId(5L);
            details.setStationId(8L);
            details.setStudentGroup(nouveauGroupe);

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(rotation));
            when(rotationRepository.save(any(Rotation.class))).thenAnswer(inv -> inv.getArgument(0));

            Rotation result = rotationService.update(1L, details);

            assertThat(result.getOrdrePassage()).isEqualTo(2);
            assertThat(result.getStatut()).isEqualTo(RotationStatus.EN_COURS);
            assertThat(result.getEvaluateurId()).isEqualTo(5L);
            assertThat(result.getStationId()).isEqualTo(8L);
            assertThat(result.getStudentGroup().getId()).isEqualTo(2L);
            assertThat(result.getDebutCreneau()).isEqualTo(LocalDateTime.of(2024, 6, 15, 10, 0));
            verify(rotationRepository).save(any(Rotation.class));
        }

        @Test
        @DisplayName("Transition statut EN_ATTENTE → TERMINE doit réussir")
        void update_devraitAccepterTransitionStatutTermine() {
            Rotation details = new Rotation();
            details.setOrdrePassage(rotation.getOrdrePassage());
            details.setDebutCreneau(rotation.getDebutCreneau());
            details.setStatut(RotationStatus.TERMINE);
            details.setEvaluateurId(rotation.getEvaluateurId());
            details.setStudentGroup(rotation.getStudentGroup());

            when(rotationRepository.findById(1L)).thenReturn(Optional.of(rotation));
            when(rotationRepository.save(any(Rotation.class))).thenAnswer(inv -> inv.getArgument(0));

            Rotation result = rotationService.update(1L, details);

            assertThat(result.getStatut()).isEqualTo(RotationStatus.TERMINE);
        }

        @Test
        @DisplayName("Doit lever RuntimeException si rotation introuvable")
        void update_devraitLeverExceptionSiIntrouvable() {
            when(rotationRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> rotationService.update(99L, new Rotation()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("99");
        }
    }
}
