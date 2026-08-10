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

    /** #274 — permissif ici : le perimetre de matiere a ses propres tests. */
    @Mock private MatiereAccessGuard matiereAccessGuard;

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

        /**
         * #274 — on charge le groupe pour remonter {@code group → lot → examenId} :
         * {@code deleteById} ne permettait aucun contrôle de périmètre.
         */
        @Test
        @DisplayName("#274 — charge le groupe, vérifie le périmètre, PUIS supprime")
        void delete_devraitVerifierLePerimetrePuisSupprimer() {
            Lot lot = new Lot();
            lot.setExamenId(42L);
            StudentGroup group = new StudentGroup();
            group.setId(1L);
            group.setLot(lot);
            when(studentGroupRepository.findById(1L)).thenReturn(Optional.of(group));

            studentGroupService.delete(1L);

            verify(matiereAccessGuard).checkExamenAccess(42L);
            verify(studentGroupRepository, times(1)).delete(group);
        }

        @Test
        @DisplayName("Un groupe DÉTACHÉ (sans lot) échoue fermé : examenId null")
        void delete_groupeDetache_perimetreNull() {
            StudentGroup orphelin = new StudentGroup();
            orphelin.setId(2L);
            when(studentGroupRepository.findById(2L)).thenReturn(Optional.of(orphelin));

            studentGroupService.delete(2L);

            // Le guard reçoit null et c'est LUI qui refuse (403) — ici mocké, donc permissif.
            // Ce qui compte : on ne contourne pas la vérification faute de lot.
            verify(matiereAccessGuard).checkExamenAccess(null);
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

        @Test
        @DisplayName("#215 : un PUT partiel (sans lotId) ne doit PAS null-ifier le lot")
        void update_putPartiel_preserveLeLot() {
            StudentGroup details = new StudentGroup();
            details.setNumeroGroupe(3);
            // lot NON fourni (comme le contrôleur sur PUT sans lotId)

            when(studentGroupRepository.findById(1L)).thenReturn(Optional.of(group));
            when(studentGroupRepository.save(any(StudentGroup.class))).thenAnswer(inv -> inv.getArgument(0));

            StudentGroup result = studentGroupService.update(1L, details);

            assertThat(result.getNumeroGroupe()).isEqualTo(3);
            assertThat(result.getLot()).isNotNull();                    // préservé
            assertThat(result.getLot().getId()).isEqualTo(1L);
        }
    }
}
