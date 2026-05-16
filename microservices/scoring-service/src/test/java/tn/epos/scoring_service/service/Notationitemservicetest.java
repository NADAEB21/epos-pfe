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
import tn.epos.scoring_service.entities.NotationItem;
import tn.epos.scoring_service.repositories.INotationItemRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotationItemService - Tests unitaires")
class NotationItemServiceTest {

    @Mock
    private INotationItemRepository repository;

    @InjectMocks
    private NotationItemService notationItemService;

    private NotationItem item;
    private Notation notation;

    @BeforeEach
    void setUp() {
        // NotationItem : id, item_id (Long), valeur (Float), commentaire, notation (ManyToOne)
        notation = new Notation();
        notation.setId(5L);
        notation.setScore_final(17.5f);
        notation.setVerouillee(false);

        item = new NotationItem();
        item.setId(1L);
        item.setItem_id(100L);
        item.setValeur(15.0f);
        item.setCommentaire("Bonne réponse");
        item.setNotation(notation);
    }

    @Nested
    @DisplayName("findAll()")
    class FindAll {

        @Test
        @DisplayName("Doit retourner tous les items de notation")
        void findAll_devraitRetournerListe() {
            when(repository.findAll()).thenReturn(List.of(item));

            List<NotationItem> result = notationItemService.findAll();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getValeur()).isEqualTo(15.0f);
            assertThat(result.get(0).getItem_id()).isEqualTo(100L);
            verify(repository, times(1)).findAll();
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucun item")
        void findAll_devraitRetournerListeVide() {
            when(repository.findAll()).thenReturn(List.of());

            List<NotationItem> result = notationItemService.findAll();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByNotation()")
    class FindByNotation {

        @Test
        @DisplayName("Doit retourner les items liés à une notation donnée")
        void findByNotation_devraitRetournerItemsDeLaNotation() {
            when(repository.findByNotationId(5L)).thenReturn(List.of(item));

            List<NotationItem> result = notationItemService.findByNotation(5L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNotation().getId()).isEqualTo(5L);
            verify(repository, times(1)).findByNotationId(5L);
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucun item pour cette notation")
        void findByNotation_devraitRetournerListeVide() {
            when(repository.findByNotationId(99L)).thenReturn(List.of());

            List<NotationItem> result = notationItemService.findByNotation(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findById()")
    class FindById {

        @Test
        @DisplayName("Doit retourner l'item si trouvé")
        void findById_devraitRetournerItem() {
            when(repository.findById(1L)).thenReturn(Optional.of(item));

            Optional<NotationItem> result = notationItemService.findById(1L);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(1L);
            assertThat(result.get().getCommentaire()).isEqualTo("Bonne réponse");
        }

        @Test
        @DisplayName("Doit retourner Optional vide si introuvable")
        void findById_devraitRetournerVideSiIntrouvable() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            Optional<NotationItem> result = notationItemService.findById(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("save()")
    class Save {

        @Test
        @DisplayName("Doit sauvegarder et retourner l'item")
        void save_devraitSauvegarderItem() {
            when(repository.save(any(NotationItem.class))).thenReturn(item);

            NotationItem result = notationItemService.save(item);

            assertThat(result).isNotNull();
            assertThat(result.getCommentaire()).isEqualTo("Bonne réponse");
            assertThat(result.getValeur()).isEqualTo(15.0f);
            verify(repository, times(1)).save(any(NotationItem.class));
        }
    }

    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("Doit appeler deleteById avec le bon ID")
        void delete_devraitAppelerDeleteById() {
            doNothing().when(repository).deleteById(1L);

            notationItemService.delete(1L);

            verify(repository, times(1)).deleteById(1L);
        }
    }

    @Nested
    @DisplayName("update()")
    class Update {

        @Test
        @DisplayName("Doit mettre à jour tous les champs de l'item si trouvé")
        void update_devraitMettreAJourItem() {
            Notation autreNotation = new Notation();
            autreNotation.setId(8L);

            NotationItem details = new NotationItem();
            details.setItem_id(200L);
            details.setValeur(18.0f);
            details.setCommentaire("Excellent");
            details.setNotation(autreNotation);

            when(repository.findById(1L)).thenReturn(Optional.of(item));
            when(repository.save(any(NotationItem.class))).thenAnswer(inv -> inv.getArgument(0));

            NotationItem result = notationItemService.update(1L, details);

            assertThat(result.getItem_id()).isEqualTo(200L);
            assertThat(result.getValeur()).isEqualTo(18.0f);
            assertThat(result.getCommentaire()).isEqualTo("Excellent");
            assertThat(result.getNotation().getId()).isEqualTo(8L);
            verify(repository).save(any(NotationItem.class));
        }

        @Test
        @DisplayName("Doit lever RuntimeException si item introuvable")
        void update_devraitLeverExceptionSiIntrouvable() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> notationItemService.update(99L, new NotationItem()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("99");
        }
    }
}
