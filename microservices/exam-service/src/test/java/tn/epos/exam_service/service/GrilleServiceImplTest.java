package tn.epos.exam_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.exam_service.dto.request.GrilleRequest;
import tn.epos.exam_service.dto.request.ItemRequest;
import tn.epos.exam_service.dto.response.GrilleResponse;
import tn.epos.exam_service.dto.response.ItemResponse;
import tn.epos.exam_service.entities.Examen;
import tn.epos.exam_service.entities.GrilleEvaluation;
import tn.epos.exam_service.entities.ItemEvaluation;
import tn.epos.exam_service.entities.Station;
import tn.epos.exam_service.enums.StatutExamen;
import tn.epos.exam_service.enums.TypeItem;
import tn.epos.exam_service.enums.TypeStation;
import tn.epos.exam_service.exception.BusinessException;
import tn.epos.exam_service.exception.ResourceNotFoundException;
import tn.epos.exam_service.repositories.GrilleEvaluationRepository;
import tn.epos.exam_service.repositories.ItemEvaluationRepository;
import tn.epos.exam_service.repositories.StationRepository;
import tn.epos.exam_service.services.impl.GrilleServiceImpl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import static org.mockito.ArgumentMatchers.eq;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GrilleService - Tests unitaires")
class GrilleServiceImplTest {

    @Mock
    private GrilleEvaluationRepository grilleRepository;

    @Mock
    private ItemEvaluationRepository itemRepository;

    @Mock
    private StationRepository stationRepository;

    @InjectMocks
    private GrilleServiceImpl grilleService;

    private Examen examenBrouillon;
    private Station station;
    private GrilleEvaluation grille;
    private GrilleRequest grilleRequest;
    private ItemRequest itemBinaireRequest;
    private ItemRequest itemNumeriqueRequest;
    private ItemEvaluation itemBinaire;

    @BeforeEach
    void setUp() {
        examenBrouillon = Examen.builder()
                .id(1L)
                .nom("Examen Test")
                .matiere("Chimie")
                .dateExamen(LocalDate.now())
                .statut(StatutExamen.BROUILLON)
                .build();

        station = Station.builder()
                .id(1L)
                .nom("Station 3")
                .type(TypeStation.PRATIQUE)
                .ordre(1)
                .examen(examenBrouillon)
                .build();

        grille = GrilleEvaluation.builder()
                .id(1L)
                .nom("Grille Station 3")
                .noteMax(20.0)
                .station(station)
                .items(new ArrayList<>())
                .build();

        grilleRequest = new GrilleRequest();
        grilleRequest.setNom("Grille Station 3");
        grilleRequest.setNoteMax(20.0);
        grilleRequest.setItems(new ArrayList<>());

        itemBinaireRequest = new ItemRequest();
        itemBinaireRequest.setLibelle("Choix de l'indicateur");
        itemBinaireRequest.setType(TypeItem.BINAIRE);
        itemBinaireRequest.setPonderation(2.0);

        itemNumeriqueRequest = new ItemRequest();
        itemNumeriqueRequest.setLibelle("Calcul de la masse");
        itemNumeriqueRequest.setType(TypeItem.NUMERIQUE);
        itemNumeriqueRequest.setPonderation(6.0);
        itemNumeriqueRequest.setValeurMax(6.0);

        itemBinaire = ItemEvaluation.builder()
                .id(1L)
                .libelle("Choix de l'indicateur")
                .type(TypeItem.BINAIRE)
                .ponderation(2.0)
                .ordre(1)
                .grille(grille)
                .build();
    }

    // ================================================================
    // CREER GRILLE
    // ================================================================

    @Nested
    @DisplayName("creerPourStation()")
    class CreerPourStation {

        @Test
        @DisplayName("Doit créer une grille vide pour une station")
        void creer_devraitCreerGrille() {
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(grilleRepository.existsByStationId(1L)).thenReturn(false);
            when(grilleRepository.save(any(GrilleEvaluation.class))).thenReturn(grille);

            GrilleResponse result = grilleService.creerPourStation(1L, grilleRequest);

            assertThat(result).isNotNull();
            assertThat(result.getNom()).isEqualTo("Grille Station 3");
            assertThat(result.getNoteMax()).isEqualTo(20.0);
        }

        @Test
        @DisplayName("Doit lever BusinessException si grille déjà existante")
        void creer_devraitLeverExceptionSiGrilleExistante() {
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(grilleRepository.existsByStationId(1L)).thenReturn(true);

            assertThatThrownBy(() -> grilleService.creerPourStation(1L, grilleRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("déjà une grille");
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si station introuvable")
        void creer_devraitLeverExceptionSiStationIntrouvable() {
            when(stationRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> grilleService.creerPourStation(99L, grilleRequest))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Doit créer la grille avec les items inclus")
        void creer_devraitCreerGrilleAvecItems() {
            grilleRequest.setItems(List.of(itemBinaireRequest));
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(grilleRepository.existsByStationId(1L)).thenReturn(false);
            when(grilleRepository.save(any())).thenReturn(grille);

            GrilleResponse result = grilleService.creerPourStation(1L, grilleRequest);

            assertThat(result).isNotNull();
            verify(grilleRepository).save(any());
        }

        @Test
        @DisplayName("Doit lever BusinessException si examen EN_COURS")
        void creer_devraitLeverExceptionSiExamenEnCours() {
            examenBrouillon.setStatut(StatutExamen.EN_COURS);
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(grilleRepository.existsByStationId(1L)).thenReturn(false);

            assertThatThrownBy(() -> grilleService.creerPourStation(1L, grilleRequest))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ================================================================
    // TROUVER GRILLE
    // ================================================================

    @Nested
    @DisplayName("trouverParStation()")
    class TrouverParStation {

        @Test
        @DisplayName("Doit retourner la grille avec ses items")
        void trouverParStation_devraitRetournerGrille() {
            when(grilleRepository.findByStationIdWithItems(1L)).thenReturn(Optional.of(grille));

            GrilleResponse result = grilleService.trouverParStation(1L);

            assertThat(result).isNotNull();
            assertThat(result.getStationId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si aucune grille")
        void trouverParStation_devraitLeverExceptionSiAbsente() {
            when(grilleRepository.findByStationIdWithItems(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> grilleService.trouverParStation(99L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ================================================================
    // AJOUTER ITEM
    // ================================================================

    @Nested
    @DisplayName("ajouterItem()")
    class AjouterItem {

        @Test
        @DisplayName("Doit ajouter un item BINAIRE valide")
        void ajouterItem_binaire_devraitReussir() {
            when(grilleRepository.findByIdWithItems(1L)).thenReturn(Optional.of(grille));
            when(grilleRepository.save(any())).thenReturn(grille);

            ItemResponse result = grilleService.ajouterItem(1L, itemBinaireRequest);

            assertThat(result).isNotNull();
            assertThat(result.getType()).isEqualTo(TypeItem.BINAIRE);
        }

        @Test
        @DisplayName("Doit ajouter un item NUMERIQUE valide")
        void ajouterItem_numerique_devraitReussir() {
            when(grilleRepository.findByIdWithItems(1L)).thenReturn(Optional.of(grille));
            when(grilleRepository.save(any())).thenReturn(grille);

            ItemResponse result = grilleService.ajouterItem(1L, itemNumeriqueRequest);

            assertThat(result).isNotNull();
            assertThat(result.getType()).isEqualTo(TypeItem.NUMERIQUE);
        }

        @Test
        @DisplayName("Doit lever BusinessException si NUMERIQUE sans valeurMax")
        void ajouterItem_numeriqueSansValeurMax_devraitEchouer() {
            itemNumeriqueRequest.setValeurMax(null);
            when(grilleRepository.findByIdWithItems(1L)).thenReturn(Optional.of(grille));

            assertThatThrownBy(() -> grilleService.ajouterItem(1L, itemNumeriqueRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("valeurMax");
        }

        @Test
        @DisplayName("Doit lever BusinessException si pondération dépasse noteMax")
        void ajouterItem_devraitLeverExceptionSiPonderationDepasse() {
            // Grille avec 19 points déjà utilisés
            ItemEvaluation itemExistant = ItemEvaluation.builder()
                    .ponderation(19.0).type(TypeItem.BINAIRE).grille(grille).ordre(1).build();
            grille.getItems().add(itemExistant);

            itemBinaireRequest.setPonderation(2.0); // 19 + 2 = 21 > 20

            when(grilleRepository.findByIdWithItems(1L)).thenReturn(Optional.of(grille));

            assertThatThrownBy(() -> grilleService.ajouterItem(1L, itemBinaireRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("dépasser");
        }

        @Test
        @DisplayName("Doit lever BusinessException si valeurMax > pondération")
        void ajouterItem_valeurMaxSupPonderation_devraitEchouer() {
            itemNumeriqueRequest.setPonderation(4.0);
            itemNumeriqueRequest.setValeurMax(6.0); // 6 > 4 → invalide

            when(grilleRepository.findByIdWithItems(1L)).thenReturn(Optional.of(grille));

            assertThatThrownBy(() -> grilleService.ajouterItem(1L, itemNumeriqueRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("valeurMax");
        }

        @Test
        @DisplayName("Doit lever BusinessException si grille verrouillée")
        void ajouterItem_devraitLeverExceptionSiGrilleVerrouillee() {
            examenBrouillon.setStatut(StatutExamen.EN_COURS);
            when(grilleRepository.findByIdWithItems(1L)).thenReturn(Optional.of(grille));

            assertThatThrownBy(() -> grilleService.ajouterItem(1L, itemBinaireRequest))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ================================================================
    // MODIFIER ITEM
    // ================================================================

    @Nested
    @DisplayName("modifierItem()")
    class ModifierItem {

        @Test
        @DisplayName("Doit modifier un item valide")
        void modifierItem_devraitModifier() {
            when(itemRepository.findById(1L)).thenReturn(Optional.of(itemBinaire));
            when(itemRepository.save(any())).thenReturn(itemBinaire);

            ItemResponse result = grilleService.modifierItem(1L, itemBinaireRequest);

            assertThat(result).isNotNull();
            verify(itemRepository).save(any());
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si item introuvable")
        void modifierItem_devraitLeverExceptionSiIntrouvable() {
            when(itemRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> grilleService.modifierItem(99L, itemBinaireRequest))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Doit lever BusinessException si examen verrouillé")
        void modifierItem_devraitLeverExceptionSiVerrouille() {
            examenBrouillon.setStatut(StatutExamen.EN_COURS);
            when(itemRepository.findById(1L)).thenReturn(Optional.of(itemBinaire));

            assertThatThrownBy(() -> grilleService.modifierItem(1L, itemBinaireRequest))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ================================================================
    // SUPPRIMER ITEM
    // ================================================================

    @Nested
    @DisplayName("supprimerItem()")
    class SupprimerItem {

        @Test
        @DisplayName("Doit supprimer l'item et réordonner")
        void supprimerItem_devraitSupprimer() {
            grille.getItems().add(itemBinaire);
            when(itemRepository.findById(1L)).thenReturn(Optional.of(itemBinaire));
            when(grilleRepository.save(any())).thenReturn(grille);

            grilleService.supprimerItem(1L);

            verify(grilleRepository).save(any());
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si item introuvable")
        void supprimerItem_devraitLeverExceptionSiIntrouvable() {
            when(itemRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> grilleService.supprimerItem(99L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Doit lever BusinessException si examen verrouillé")
        void supprimerItem_devraitLeverExceptionSiVerrouille() {
            examenBrouillon.setStatut(StatutExamen.TERMINE);
            when(itemRepository.findById(1L)).thenReturn(Optional.of(itemBinaire));

            assertThatThrownBy(() -> grilleService.supprimerItem(1L))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ================================================================
    // LISTER ITEMS
    // ================================================================

    @Nested
    @DisplayName("listerItems()")
    class ListerItems {

        @Test
        @DisplayName("Doit retourner les items triés par ordre")
        void listerItems_devraitRetournerItems() {
            Page<ItemEvaluation> page = new PageImpl<>(List.of(itemBinaire));
            when(grilleRepository.existsById(1L)).thenReturn(true);
            when(itemRepository.findByGrilleIdOrderByOrdreAsc(eq(1L), any(Pageable.class)))
                    .thenReturn(page);

            Page<ItemResponse> result = grilleService.listerItems(1L, Pageable.unpaged());

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si grille introuvable")
        void listerItems_devraitLeverExceptionSiGrilleIntrouvable() {
            when(grilleRepository.existsById(99L)).thenReturn(false);

            assertThatThrownBy(() -> grilleService.listerItems(99L, Pageable.unpaged()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ================================================================
    // SUPPRIMER GRILLE
    // ================================================================

    @Nested
    @DisplayName("supprimer()")
    class SupprimerGrille {

        @Test
        @DisplayName("Doit supprimer la grille si examen modifiable")
        void supprimer_devraitSupprimerGrille() {
            when(grilleRepository.findById(1L)).thenReturn(Optional.of(grille));

            grilleService.supprimer(1L);

            verify(grilleRepository).delete(grille);
        }

        @Test
        @DisplayName("Doit lever BusinessException si examen EN_COURS")
        void supprimer_devraitLeverExceptionSiEnCours() {
            examenBrouillon.setStatut(StatutExamen.EN_COURS);
            when(grilleRepository.findById(1L)).thenReturn(Optional.of(grille));

            assertThatThrownBy(() -> grilleService.supprimer(1L))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
