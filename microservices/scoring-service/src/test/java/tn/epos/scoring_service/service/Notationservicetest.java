package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.repositories.INotationRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotationService - Tests unitaires")
class NotationServiceTest {

    @Mock
    private INotationRepository repository;

    @InjectMocks
    private NotationService notationService;

    private Notation notation;

    @BeforeEach
    void setUp() {
        notation = new Notation();
        notation.setId(1L);
        notation.setScore_final(17.5f);
        notation.setTemps_additionnel(0);
        notation.setIs_synced(false);
        notation.setVerouillee(false);
        notation.setStationId(7L);
        notation.setGrilleId(11L);
        // timestamp géré par @PrePersist — pas de setter manuel nécessaire
    }

    @Nested
    @DisplayName("findAll()")
    class FindAll {

        @Test
        @DisplayName("Doit retourner toutes les notations")
        void findAll_devraitRetournerListe() {
            when(repository.findAll()).thenReturn(List.of(notation));

            List<Notation> result = notationService.findAll();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getScore_final()).isEqualTo(17.5f);
            assertThat(result.get(0).getVerouillee()).isFalse();
            verify(repository, times(1)).findAll();
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucune notation")
        void findAll_devraitRetournerListeVide() {
            when(repository.findAll()).thenReturn(List.of());

            List<Notation> result = notationService.findAll();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findById()")
    class FindById {

        @Test
        @DisplayName("Doit retourner la notation si trouvée")
        void findById_devraitRetournerNotation() {
            when(repository.findById(1L)).thenReturn(Optional.of(notation));

            Optional<Notation> result = notationService.findById(1L);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Doit retourner Optional vide si introuvable")
        void findById_devraitRetournerVideSiIntrouvable() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            Optional<Notation> result = notationService.findById(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByAssignment()")
    class FindByAssignment {

        @Test
        @DisplayName("Doit retourner la notation liée à un assignment")
        void findByAssignment_devraitRetournerNotation() {
            when(repository.findByAssignmentId(42L)).thenReturn(Optional.of(notation));

            Optional<Notation> result = notationService.findByAssignment(42L);

            assertThat(result).isPresent();
            assertThat(result.get().getScore_final()).isEqualTo(17.5f);
        }

        @Test
        @DisplayName("Doit retourner Optional vide si aucune notation pour cet assignment")
        void findByAssignment_devraitRetournerVideSiAucune() {
            when(repository.findByAssignmentId(99L)).thenReturn(Optional.empty());

            Optional<Notation> result = notationService.findByAssignment(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByStation()")
    class FindByStation {

        @Test
        @DisplayName("Doit retourner les notations d'une station donnée")
        void findByStation_devraitRetournerNotationsDeLaStation() {
            when(repository.findByStationId(7L)).thenReturn(List.of(notation));

            List<Notation> result = notationService.findByStation(7L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStationId()).isEqualTo(7L);
            verify(repository, times(1)).findByStationId(7L);
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucune notation pour cette station")
        void findByStation_devraitRetournerListeVide() {
            when(repository.findByStationId(99L)).thenReturn(List.of());

            List<Notation> result = notationService.findByStation(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByGrille()")
    class FindByGrille {

        @Test
        @DisplayName("Doit retourner les notations d'une grille donnée")
        void findByGrille_devraitRetournerNotationsDeLaGrille() {
            when(repository.findByGrilleId(11L)).thenReturn(List.of(notation));

            List<Notation> result = notationService.findByGrille(11L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getGrilleId()).isEqualTo(11L);
            verify(repository, times(1)).findByGrilleId(11L);
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucune notation pour cette grille")
        void findByGrille_devraitRetournerListeVide() {
            when(repository.findByGrilleId(99L)).thenReturn(List.of());

            List<Notation> result = notationService.findByGrille(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("save()")
    class Save {

        @Test
        @DisplayName("Doit sauvegarder et retourner la notation")
        void save_devraitSauvegarder() {
            when(repository.save(any(Notation.class))).thenReturn(notation);

            Notation result = notationService.save(notation);

            assertThat(result).isNotNull();
            assertThat(result.getScore_final()).isEqualTo(17.5f);
            assertThat(result.getIs_synced()).isFalse();
            verify(repository, times(1)).save(any(Notation.class));
        }
    }

    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("Doit appeler deleteById avec le bon ID")
        void delete_devraitAppelerDeleteById() {
            doNothing().when(repository).deleteById(1L);

            notationService.delete(1L);

            verify(repository, times(1)).deleteById(1L);
        }
    }

    @Nested
    @DisplayName("update()")
    class Update {

        @Test
        @DisplayName("Doit mettre à jour la notation si non verrouillée")
        void update_devraitMettreAJourSiNonVerrouillee() {
            Notation details = new Notation();
            details.setScore_final(20.0f);
            details.setIs_synced(true);
            details.setVerouillee(false);
            details.setTemps_additionnel(5);
            details.setStationId(8L);
            details.setGrilleId(12L);

            when(repository.findById(1L)).thenReturn(Optional.of(notation));
            when(repository.save(any(Notation.class))).thenAnswer(inv -> inv.getArgument(0));

            Notation result = notationService.update(1L, details);

            assertThat(result.getScore_final()).isEqualTo(20.0f);
            assertThat(result.getIs_synced()).isTrue();
            assertThat(result.getTemps_additionnel()).isEqualTo(5);
            assertThat(result.getStationId()).isEqualTo(8L);
            assertThat(result.getGrilleId()).isEqualTo(12L);
            verify(repository).save(any(Notation.class));
        }

        @Test
        @DisplayName("Doit lever RuntimeException si la notation est verrouillée")
        void update_devraitLeverExceptionSiVerrouillee() {
            notation.setVerouillee(true);
            when(repository.findById(1L)).thenReturn(Optional.of(notation));

            assertThatThrownBy(() -> notationService.update(1L, new Notation()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("verrouillée");
        }

        @Test
        @DisplayName("Doit lever RuntimeException si notation introuvable")
        void update_devraitLeverExceptionSiIntrouvable() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> notationService.update(99L, new Notation()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("99");
        }
    }

    @Nested
    @DisplayName("verrouiller()")
    class Verrouiller {

        @Test
        @DisplayName("Doit passer verouillee à true et sauvegarder")
        void verrouiller_devraitVerrouillerEtSauvegarder() {
            when(repository.findById(1L)).thenReturn(Optional.of(notation));
            when(repository.save(any(Notation.class))).thenAnswer(inv -> inv.getArgument(0));

            Notation result = notationService.verrouiller(1L);

            assertThat(result.getVerouillee()).isTrue();
            verify(repository).save(any(Notation.class));
        }

        @Test
        @DisplayName("Une notation déjà verrouillée reste verrouillée après un second appel")
        void verrouiller_devraitResterVerrouilleeSiDejaVerrouillee() {
            notation.setVerouillee(true);
            when(repository.findById(1L)).thenReturn(Optional.of(notation));
            when(repository.save(any(Notation.class))).thenAnswer(inv -> inv.getArgument(0));

            Notation result = notationService.verrouiller(1L);

            assertThat(result.getVerouillee()).isTrue();
        }

        @Test
        @DisplayName("Doit lever RuntimeException si notation introuvable lors du verrouillage")
        void verrouiller_devraitLeverExceptionSiIntrouvable() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> notationService.verrouiller(99L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("99");
        }
    }
}
