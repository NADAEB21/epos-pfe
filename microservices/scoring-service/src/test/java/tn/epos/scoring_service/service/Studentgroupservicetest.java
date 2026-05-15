package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.entities.LotStatus;
import tn.epos.scoring_service.entities.StudentGroup;
import tn.epos.scoring_service.repositories.IStudentGroupRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StudentGroupService - Tests unitaires")
class StudentGroupServiceTest {

    @Mock
    private IStudentGroupRepository studentGroupRepository;

    @InjectMocks
    private StudentGroupService studentGroupService;

    // StudentGroup : id, numeroGroupe (Integer), lot (ManyToOne), rotations (OneToMany)
    private StudentGroup group;
    private Lot lot;

    @BeforeEach
    void setUp() {
        lot = new Lot();
        lot.setId(1L);
        lot.setNumeroLot(1);
        lot.setTailleLot(20);
        lot.setExamenId(10L);
        lot.setEvaluateurId(5L);
        lot.setStatut(LotStatus.EN_ATTENTE);

        group = new StudentGroup();
        group.setId(1L);
        group.setNumeroGroupe(1);
        group.setLot(lot);
    }

    @Nested
    @DisplayName("findAll()")
    class FindAll {

        @Test
        @DisplayName("Doit retourner tous les groupes")
        void findAll_devraitRetournerListe() {
            when(studentGroupRepository.findAll()).thenReturn(List.of(group));

            List<StudentGroup> result = studentGroupService.findAll();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNumeroGroupe()).isEqualTo(1);
            assertThat(result.get(0).getLot().getId()).isEqualTo(1L);
            verify(studentGroupRepository, times(1)).findAll();
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucun groupe")
        void findAll_devraitRetournerListeVide() {
            when(studentGroupRepository.findAll()).thenReturn(List.of());

            List<StudentGroup> result = studentGroupService.findAll();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findById()")
    class FindById {

        @Test
        @DisplayName("Doit retourner le groupe si trouvé")
        void findById_devraitRetournerGroupe() {
            when(studentGroupRepository.findById(1L)).thenReturn(Optional.of(group));

            Optional<StudentGroup> result = studentGroupService.findById(1L);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(1L);
            assertThat(result.get().getLot().getNumeroLot()).isEqualTo(1);
        }

        @Test
        @DisplayName("Doit retourner Optional vide si introuvable")
        void findById_devraitRetournerVideSiIntrouvable() {
            when(studentGroupRepository.findById(99L)).thenReturn(Optional.empty());

            Optional<StudentGroup> result = studentGroupService.findById(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByLotId()")
    class FindByLotId {

        @Test
        @DisplayName("Doit retourner les groupes appartenant à un lot")
        void findByLotId_devraitRetournerGroupesDuLot() {
            when(studentGroupRepository.findByLotId(1L)).thenReturn(List.of(group));

            List<StudentGroup> result = studentGroupService.findByLotId(1L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getLot().getId()).isEqualTo(1L);
            verify(studentGroupRepository, times(1)).findByLotId(1L);
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucun groupe pour ce lot")
        void findByLotId_devraitRetournerListeVide() {
            when(studentGroupRepository.findByLotId(99L)).thenReturn(List.of());

            List<StudentGroup> result = studentGroupService.findByLotId(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("save()")
    class Save {

        @Test
        @DisplayName("Doit sauvegarder et retourner le groupe avec son lot")
        void save_devraitSauvegarder() {
            when(studentGroupRepository.save(any(StudentGroup.class))).thenReturn(group);

            StudentGroup result = studentGroupService.save(group);

            assertThat(result).isNotNull();
            assertThat(result.getNumeroGroupe()).isEqualTo(1);
            assertThat(result.getLot().getStatut()).isEqualTo(LotStatus.EN_ATTENTE);
            verify(studentGroupRepository, times(1)).save(any(StudentGroup.class));
        }
    }

    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("Doit appeler deleteById avec le bon ID")
        void delete_devraitAppelerDeleteById() {
            doNothing().when(studentGroupRepository).deleteById(1L);

            studentGroupService.delete(1L);

            verify(studentGroupRepository, times(1)).deleteById(1L);
        }
    }

    @Nested
    @DisplayName("update()")
    class Update {

        @Test
        @DisplayName("Doit mettre à jour numeroGroupe et lot si groupe trouvé")
        void update_devraitMettreAJourGroupe() {
            Lot nouveauLot = new Lot();
            nouveauLot.setId(2L);
            nouveauLot.setNumeroLot(2);
            nouveauLot.setStatut(LotStatus.EN_COURS);

            StudentGroup details = new StudentGroup();
            details.setNumeroGroupe(3);
            details.setLot(nouveauLot);

            when(studentGroupRepository.findById(1L)).thenReturn(Optional.of(group));
            when(studentGroupRepository.save(any(StudentGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            StudentGroup result = studentGroupService.update(1L, details);

            assertThat(result.getNumeroGroupe()).isEqualTo(3);
            assertThat(result.getLot().getId()).isEqualTo(2L);
            assertThat(result.getLot().getStatut()).isEqualTo(LotStatus.EN_COURS);
            verify(studentGroupRepository).save(any(StudentGroup.class));
        }

        @Test
        @DisplayName("Doit lever RuntimeException si groupe introuvable")
        void update_devraitLeverExceptionSiIntrouvable() {
            when(studentGroupRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> studentGroupService.update(99L, new StudentGroup()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("99");
        }
    }
}
