package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.common.exception.BusinessException;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.entities.NotationItem;
import tn.epos.scoring_service.repositories.INotationItemRepository;
import tn.epos.scoring_service.repositories.INotationRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotationItemService - Tests unitaires")
class NotationItemServiceTest {

    @Mock
    private INotationItemRepository repository;

    @Mock
    private INotationRepository notationRepository;

    @Mock
    private ExamServiceClient examServiceClient;

    @InjectMocks
    private NotationItemService notationItemService;

    private NotationItem item;
    private Notation notation;

    @BeforeEach
    void setUp() {
        notation = new Notation();
        notation.setId(5L);
        notation.setScore_final(17.5f);
        notation.setVerouillee(false);
        notation.setStationId(7L);
        notation.setGrilleId(11L);

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
    @DisplayName("save() - validation cross-grille (#84)")
    class Save {

        @Test
        @DisplayName("Doit sauvegarder si item_id appartient bien à la grille de la notation")
        void save_devraitSauvegarderSiItemDansLaGrille() {
            when(notationRepository.findById(5L)).thenReturn(Optional.of(notation));
            when(examServiceClient.getItemIdsForGrille(11L)).thenReturn(Set.of(100L, 101L, 102L));
            when(repository.save(any(NotationItem.class))).thenReturn(item);

            NotationItem result = notationItemService.save(item);

            assertThat(result).isNotNull();
            assertThat(result.getItem_id()).isEqualTo(100L);
            verify(examServiceClient).getItemIdsForGrille(11L);
            verify(repository).save(item);
        }

        @Test
        @DisplayName("Doit rejeter si item_id n'appartient pas à la grille de la notation")
        void save_devraitRejeterSiItemHorsGrille() {
            when(notationRepository.findById(5L)).thenReturn(Optional.of(notation));
            // grille 11 ne contient pas l'item 100
            when(examServiceClient.getItemIdsForGrille(11L)).thenReturn(Set.of(200L, 201L));

            assertThatThrownBy(() -> notationItemService.save(item))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("cross-grille refusé");

            verify(repository, never()).save(any(NotationItem.class));
        }

        @Test
        @DisplayName("Doit ignorer la validation si la notation parente n'a pas de grille_id")
        void save_devraitIgnorerSiGrilleIdNullSurNotation() {
            notation.setGrilleId(null);
            when(notationRepository.findById(5L)).thenReturn(Optional.of(notation));
            when(repository.save(any(NotationItem.class))).thenReturn(item);

            NotationItem result = notationItemService.save(item);

            assertThat(result).isNotNull();
            verify(examServiceClient, never()).getItemIdsForGrille(any());
            verify(repository).save(item);
        }

        @Test
        @DisplayName("Doit propager BusinessException si exam-service est injoignable")
        void save_devraitPropagerSiExamServiceInjoignable() {
            when(notationRepository.findById(5L)).thenReturn(Optional.of(notation));
            when(examServiceClient.getItemIdsForGrille(11L))
                    .thenThrow(new BusinessException(
                            "Validation des items impossible : exam-service injoignable"));

            assertThatThrownBy(() -> notationItemService.save(item))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("exam-service injoignable");

            verify(repository, never()).save(any(NotationItem.class));
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si la notation parente est introuvable")
        void save_devraitLeverSiNotationParenteIntrouvable() {
            when(notationRepository.findById(5L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> notationItemService.save(item))
                    .isInstanceOf(tn.epos.common.exception.ResourceNotFoundException.class)
                    .hasMessageContaining("Notation parente introuvable")
                    .hasMessageContaining("5");

            verify(examServiceClient, never()).getItemIdsForGrille(any());
            verify(repository, never()).save(any(NotationItem.class));
        }

        @Test
        @DisplayName("Doit rejeter si item_id est null malgré une grille valide")
        void save_devraitRejeterSiItemIdNull() {
            item.setItem_id(null);
            when(notationRepository.findById(5L)).thenReturn(Optional.of(notation));

            assertThatThrownBy(() -> notationItemService.save(item))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("item_id est requis");

            verify(examServiceClient, never()).getItemIdsForGrille(any());
            verify(repository, never()).save(any(NotationItem.class));
        }

        @Test
        @DisplayName("Doit ignorer la validation si l'item n'a pas de référence notation")
        void save_devraitIgnorerSiNotationNull() {
            item.setNotation(null);
            when(repository.save(any(NotationItem.class))).thenReturn(item);

            NotationItem result = notationItemService.save(item);

            assertThat(result).isNotNull();
            verify(notationRepository, never()).findById(any());
            verify(examServiceClient, never()).getItemIdsForGrille(any());
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
    @DisplayName("update() - validation cross-grille (#84)")
    class Update {

        @Test
        @DisplayName("Doit mettre à jour si le nouvel item_id appartient à la grille")
        void update_devraitMettreAJourItem() {
            Notation autreNotation = new Notation();
            autreNotation.setId(8L);
            autreNotation.setGrilleId(11L); // même grille

            NotationItem details = new NotationItem();
            details.setItem_id(101L);
            details.setValeur(18.0f);
            details.setCommentaire("Excellent");
            details.setNotation(autreNotation);

            when(repository.findById(1L)).thenReturn(Optional.of(item));
            when(notationRepository.findById(8L)).thenReturn(Optional.of(autreNotation));
            when(examServiceClient.getItemIdsForGrille(11L)).thenReturn(Set.of(100L, 101L));
            when(repository.save(any(NotationItem.class))).thenAnswer(inv -> inv.getArgument(0));

            NotationItem result = notationItemService.update(1L, details);

            assertThat(result.getItem_id()).isEqualTo(101L);
            assertThat(result.getValeur()).isEqualTo(18.0f);
            assertThat(result.getCommentaire()).isEqualTo("Excellent");
            assertThat(result.getNotation().getId()).isEqualTo(8L);
            verify(repository).save(any(NotationItem.class));
        }

        @Test
        @DisplayName("Doit rejeter l'update si le nouvel item_id n'appartient pas à la grille")
        void update_devraitRejeterCrossGrille() {
            NotationItem details = new NotationItem();
            details.setItem_id(999L); // pas dans la grille
            details.setValeur(18.0f);
            details.setCommentaire("Forbidden");
            details.setNotation(notation);

            when(repository.findById(1L)).thenReturn(Optional.of(item));
            when(notationRepository.findById(5L)).thenReturn(Optional.of(notation));
            when(examServiceClient.getItemIdsForGrille(11L)).thenReturn(Set.of(100L, 101L));

            assertThatThrownBy(() -> notationItemService.update(1L, details))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("cross-grille refusé");

            verify(repository, never()).save(any(NotationItem.class));
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si item introuvable")
        void update_devraitLeverExceptionSiIntrouvable() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> notationItemService.update(99L, new NotationItem()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("99");
        }
    }
}
