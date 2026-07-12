package tn.epos.exam_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import tn.epos.exam_service.config.MatiereAccessChecker;
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
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
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

    @Mock
    private MatiereAccessChecker matiereAccessChecker;

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
                .matiereId(1L)
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
    // #161 — REMPLACER GRILLE (create-or-replace idempotent, en place)
    // ================================================================

    @Nested
    @DisplayName("remplacerPourStation()")
    class RemplacerPourStation {

        @Test
        @DisplayName("Doit remplacer EN PLACE la grille existante sans delete (pas de 23505)")
        void remplacer_grilleExistante_devraitReutiliserLaLigne() {
            ItemEvaluation ancien = ItemEvaluation.builder()
                    .libelle("ancien critère").type(TypeItem.BINAIRE).ponderation(5.0)
                    .ordre(1).grille(grille).build();
            grille.getItems().add(ancien);
            grilleRequest.setItems(List.of(itemBinaireRequest)); // nouveau contenu

            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(grilleRepository.findByStationIdWithItems(1L)).thenReturn(Optional.of(grille));
            when(grilleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            GrilleResponse result = grilleService.remplacerPourStation(1L, grilleRequest);

            assertThat(result).isNotNull();
            // La MÊME ligne (id conservé) est réutilisée, jamais supprimée → aucun
            // conflit de contrainte unique station_id possible.
            verify(grilleRepository).save(argThat(g ->
                    g.getId() != null && g.getId().equals(1L)          // ligne existante conservée
                            && g.getItems().size() == 1                 // anciens critères purgés
                            && "Choix de l'indicateur".equals(g.getItems().get(0).getLibelle())));
            verify(grilleRepository, never()).delete(any());
        }

        @Test
        @DisplayName("Doit créer la grille si la station n'en a pas encore")
        void remplacer_grilleAbsente_devraitCreer() {
            grilleRequest.setItems(List.of(itemBinaireRequest));
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(grilleRepository.findByStationIdWithItems(1L)).thenReturn(Optional.empty());
            when(grilleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            GrilleResponse result = grilleService.remplacerPourStation(1L, grilleRequest);

            assertThat(result).isNotNull();
            assertThat(result.getNom()).isEqualTo("Grille Station 3");
            verify(grilleRepository).save(any());
            verify(grilleRepository, never()).delete(any());
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si station introuvable")
        void remplacer_stationIntrouvable_devraitEchouer() {
            when(stationRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> grilleService.remplacerPourStation(99L, grilleRequest))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Doit lever BusinessException si examen non modifiable")
        void remplacer_examenVerrouille_devraitEchouer() {
            examenBrouillon.setStatut(StatutExamen.EN_COURS);
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));

            assertThatThrownBy(() -> grilleService.remplacerPourStation(1L, grilleRequest))
                    .isInstanceOf(BusinessException.class);
            verify(grilleRepository, never()).save(any());
        }

        @Test
        @DisplayName("Régression delete→create : après supprimer(), creerPourStation() réussit sans erreur")
        void deletePuisCreate_devraitReussir() {
            // supprimer() valide + delete (transaction propre committée côté HTTP).
            when(grilleRepository.findById(1L)).thenReturn(Optional.of(grille));
            grilleService.supprimer(1L);
            verify(grilleRepository).delete(grille);

            // create ensuite : la station n'a plus de grille → existsByStationId=false → OK.
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(grilleRepository.existsByStationId(1L)).thenReturn(false);
            when(grilleRepository.save(any())).thenReturn(grille);

            GrilleResponse result = grilleService.creerPourStation(1L, grilleRequest);
            assertThat(result).isNotNull();
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
        @DisplayName("Doit propager la clé de réponse (#162) valeurAttendue + conditionsAttendues dans la réponse")
        void ajouterItem_devraitPropagerCleDeReponse() {
            itemNumeriqueRequest.setValeurAttendue(4.5);
            itemNumeriqueRequest.setConditionsAttendues("Masse comprise entre 4 et 5 g");
            when(grilleRepository.findByIdWithItems(1L)).thenReturn(Optional.of(grille));
            when(grilleRepository.save(any())).thenReturn(grille);

            ItemResponse result = grilleService.ajouterItem(1L, itemNumeriqueRequest);

            assertThat(result.getValeurAttendue()).isEqualTo(4.5);
            assertThat(result.getConditionsAttendues()).isEqualTo("Masse comprise entre 4 et 5 g");
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
            when(grilleRepository.findById(1L)).thenReturn(Optional.of(grille));
            when(itemRepository.findByGrilleIdAndParentIsNullOrderByOrdreAsc(eq(1L), any(Pageable.class)))
                    .thenReturn(page);

            Page<ItemResponse> result = grilleService.listerItems(1L, Pageable.unpaged());

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si grille introuvable")
        void listerItems_devraitLeverExceptionSiGrilleIntrouvable() {
            when(grilleRepository.findById(99L)).thenReturn(Optional.empty());

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

    // ================================================================
    // AJOUTER SOUS-CRITERE (#160)
    // ================================================================

    @Nested
    @DisplayName("ajouterSousCritere()")
    class AjouterSousCritere {

        private ItemRequest sousCritereRequest;

        @BeforeEach
        void setUpSousCritere() {
            sousCritereRequest = new ItemRequest();
            sousCritereRequest.setLibelle("Sous-critère 1");
            sousCritereRequest.setType(TypeItem.BINAIRE);
            sousCritereRequest.setPonderation(1.0);
        }

        @Test
        @DisplayName("Doit ajouter un sous-critère à un item parent valide")
        void ajouterSousCritere_devraitReussir() {
            itemBinaire.setPonderation(2.0);
            sousCritereRequest.setPonderation(2.0); // somme enfants == pondération parent
            when(itemRepository.findById(1L)).thenReturn(Optional.of(itemBinaire));
            when(itemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ItemResponse result = grilleService.ajouterSousCritere(1L, sousCritereRequest);

            assertThat(result).isNotNull();
            assertThat(result.getParentId()).isEqualTo(1L);
            assertThat(itemBinaire.getChildren()).hasSize(1);
        }

        @Test
        @DisplayName("Doit lever BusinessException si le parent est déjà un sous-critère (profondeur unique)")
        void ajouterSousCritere_parentEstDejaSousCritere_devraitEchouer() {
            ItemEvaluation grandParent = ItemEvaluation.builder()
                    .id(2L).libelle("Parent").type(TypeItem.BINAIRE).ponderation(5.0)
                    .grille(grille).build();
            itemBinaire.setParent(grandParent);
            when(itemRepository.findById(1L)).thenReturn(Optional.of(itemBinaire));

            assertThatThrownBy(() -> grilleService.ajouterSousCritere(1L, sousCritereRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("un seul niveau");
            verify(itemRepository, never()).save(any());
        }

        @Test
        @DisplayName("Doit lever BusinessException si la somme des pondérations dépasse celle du parent")
        void ajouterSousCritere_ponderationDepasse_devraitEchouer() {
            itemBinaire.setPonderation(2.0);
            sousCritereRequest.setPonderation(3.0); // > 2.0

            when(itemRepository.findById(1L)).thenReturn(Optional.of(itemBinaire));

            assertThatThrownBy(() -> grilleService.ajouterSousCritere(1L, sousCritereRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("dépasserait");
        }

        @Test
        @DisplayName("Doit lever BusinessException si l'examen n'est pas modifiable")
        void ajouterSousCritere_examenVerrouille_devraitEchouer() {
            examenBrouillon.setStatut(StatutExamen.EN_COURS);
            when(itemRepository.findById(1L)).thenReturn(Optional.of(itemBinaire));

            assertThatThrownBy(() -> grilleService.ajouterSousCritere(1L, sousCritereRequest))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si le critère parent est introuvable")
        void ajouterSousCritere_parentIntrouvable_devraitEchouer() {
            when(itemRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> grilleService.ajouterSousCritere(99L, sousCritereRequest))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ================================================================
    // LISTER SOUS-CRITERES (#160)
    // ================================================================

    @Nested
    @DisplayName("listerSousCriteres()")
    class ListerSousCriteres {

        @Test
        @DisplayName("Doit retourner les sous-critères triés par ordre")
        void listerSousCriteres_devraitRetournerListe() {
            ItemEvaluation enfant = ItemEvaluation.builder()
                    .id(5L).libelle("Enfant").type(TypeItem.BINAIRE).ponderation(1.0)
                    .ordre(1).parent(itemBinaire).grille(grille).build();
            when(itemRepository.findById(1L)).thenReturn(Optional.of(itemBinaire));
            when(itemRepository.findByParentIdOrderByOrdreAsc(1L)).thenReturn(List.of(enfant));

            List<ItemResponse> result = grilleService.listerSousCriteres(1L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getLibelle()).isEqualTo("Enfant");
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si le parent est introuvable")
        void listerSousCriteres_parentIntrouvable_devraitEchouer() {
            when(itemRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> grilleService.listerSousCriteres(99L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ================================================================
    // LISTER ITEMS FEUILLES (#160)
    // ================================================================

    @Nested
    @DisplayName("listerItemsFeuilles()")
    class ListerItemsFeuilles {

        @Test
        @DisplayName("Doit retourner uniquement les items sans sous-critères (aplatissement)")
        void listerItemsFeuilles_devraitAplatirHierarchie() {
            ItemEvaluation parent = ItemEvaluation.builder()
                    .id(10L).libelle("Parent avec enfants").type(TypeItem.BINAIRE)
                    .ponderation(4.0).ordre(1).grille(grille).build();
            ItemEvaluation enfant1 = ItemEvaluation.builder()
                    .id(11L).libelle("Enfant 1").type(TypeItem.BINAIRE)
                    .ponderation(2.0).ordre(1).parent(parent).grille(grille).build();
            ItemEvaluation enfant2 = ItemEvaluation.builder()
                    .id(12L).libelle("Enfant 2").type(TypeItem.BINAIRE)
                    .ponderation(2.0).ordre(2).parent(parent).grille(grille).build();
            parent.getChildren().addAll(List.of(enfant1, enfant2));

            ItemEvaluation feuilleSeule = ItemEvaluation.builder()
                    .id(13L).libelle("Feuille seule").type(TypeItem.BINAIRE)
                    .ponderation(1.0).ordre(2).grille(grille).build();

            grille.getItems().addAll(List.of(parent, feuilleSeule));

            when(grilleRepository.findByIdWithItems(1L)).thenReturn(Optional.of(grille));

            List<ItemResponse> result = grilleService.listerItemsFeuilles(1L);

            assertThat(result).hasSize(3);
            assertThat(result).extracting(ItemResponse::getLibelle)
                    .containsExactlyInAnyOrder("Enfant 1", "Enfant 2", "Feuille seule");
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si la grille est introuvable")
        void listerItemsFeuilles_grilleIntrouvable_devraitEchouer() {
            when(grilleRepository.findByIdWithItems(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> grilleService.listerItemsFeuilles(99L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ================================================================
    // #96 — per-matiere authz: MatiereAccessChecker is consulted after
    // every entity load. Each endpoint that mutates or reads a grille/item
    // must reject an out-of-scope caller before doing anything.
    // ================================================================

    @Nested
    @DisplayName("MatiereAccessChecker enforcement (#96)")
    class MatiereScopeEnforcement {

        @Test
        @DisplayName("creerPourStation() rejects when caller is out of scope")
        void creer_outOfScope_devraitRefuser() {
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            assertThatThrownBy(() -> grilleService.creerPourStation(1L, grilleRequest))
                    .isInstanceOf(AccessDeniedException.class);
            verify(grilleRepository, never()).save(any());
        }

        @Test
        @DisplayName("remplacerPourStation() rejects when caller is out of scope")
        void remplacer_outOfScope_devraitRefuser() {
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            assertThatThrownBy(() -> grilleService.remplacerPourStation(1L, grilleRequest))
                    .isInstanceOf(AccessDeniedException.class);
            verify(grilleRepository, never()).save(any());
        }

        @Test
        @DisplayName("trouverParStation() rejects when caller is out of scope")
        void trouverParStation_outOfScope_devraitRefuser() {
            when(grilleRepository.findByStationIdWithItems(1L)).thenReturn(Optional.of(grille));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkReadAccess(1L);

            assertThatThrownBy(() -> grilleService.trouverParStation(1L))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("trouverParId() rejects when caller is out of scope")
        void trouverParId_outOfScope_devraitRefuser() {
            when(grilleRepository.findByIdWithItems(1L)).thenReturn(Optional.of(grille));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkReadAccess(1L);

            assertThatThrownBy(() -> grilleService.trouverParId(1L))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("modifier() rejects when caller is out of scope")
        void modifier_outOfScope_devraitRefuser() {
            when(grilleRepository.findById(1L)).thenReturn(Optional.of(grille));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            assertThatThrownBy(() -> grilleService.modifier(1L, grilleRequest))
                    .isInstanceOf(AccessDeniedException.class);
            verify(grilleRepository, never()).save(any());
        }

        @Test
        @DisplayName("supprimer() rejects when caller is out of scope")
        void supprimer_outOfScope_devraitRefuser() {
            when(grilleRepository.findById(1L)).thenReturn(Optional.of(grille));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            assertThatThrownBy(() -> grilleService.supprimer(1L))
                    .isInstanceOf(AccessDeniedException.class);
            verify(grilleRepository, never()).delete(any());
        }

        @Test
        @DisplayName("ajouterItem() rejects when caller is out of scope")
        void ajouterItem_outOfScope_devraitRefuser() {
            when(grilleRepository.findByIdWithItems(1L)).thenReturn(Optional.of(grille));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            assertThatThrownBy(() -> grilleService.ajouterItem(1L, itemBinaireRequest))
                    .isInstanceOf(AccessDeniedException.class);
            verify(grilleRepository, never()).save(any());
        }

        @Test
        @DisplayName("listerItems() rejects when caller is out of scope")
        void listerItems_outOfScope_devraitRefuser() {
            when(grilleRepository.findById(1L)).thenReturn(Optional.of(grille));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkReadAccess(1L);

            assertThatThrownBy(() -> grilleService.listerItems(1L, Pageable.unpaged()))
                    .isInstanceOf(AccessDeniedException.class);
            verify(itemRepository, never()).findByGrilleIdOrderByOrdreAsc(anyLong(), any(Pageable.class));
        }

        @Test
        @DisplayName("modifierItem() rejects when caller is out of scope")
        void modifierItem_outOfScope_devraitRefuser() {
            when(itemRepository.findById(1L)).thenReturn(Optional.of(itemBinaire));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            assertThatThrownBy(() -> grilleService.modifierItem(1L, itemBinaireRequest))
                    .isInstanceOf(AccessDeniedException.class);
            verify(itemRepository, never()).save(any());
        }

        @Test
        @DisplayName("supprimerItem() rejects when caller is out of scope")
        void supprimerItem_outOfScope_devraitRefuser() {
            when(itemRepository.findById(1L)).thenReturn(Optional.of(itemBinaire));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            assertThatThrownBy(() -> grilleService.supprimerItem(1L))
                    .isInstanceOf(AccessDeniedException.class);
            verify(grilleRepository, never()).save(any());
        }

        @Test
        @DisplayName("ajouterSousCritere() rejects when caller is out of scope")
        void ajouterSousCritere_outOfScope_devraitRefuser() {
            when(itemRepository.findById(1L)).thenReturn(Optional.of(itemBinaire));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            ItemRequest req = new ItemRequest();
            req.setLibelle("x"); req.setType(TypeItem.BINAIRE); req.setPonderation(1.0);

            assertThatThrownBy(() -> grilleService.ajouterSousCritere(1L, req))
                    .isInstanceOf(AccessDeniedException.class);
            verify(itemRepository, never()).save(any());
        }

        @Test
        @DisplayName("listerSousCriteres() rejects when caller is out of scope")
        void listerSousCriteres_outOfScope_devraitRefuser() {
            when(itemRepository.findById(1L)).thenReturn(Optional.of(itemBinaire));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkReadAccess(1L);

            assertThatThrownBy(() -> grilleService.listerSousCriteres(1L))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("listerItemsFeuilles() rejects when caller is out of scope")
        void listerItemsFeuilles_outOfScope_devraitRefuser() {
            when(grilleRepository.findByIdWithItems(1L)).thenReturn(Optional.of(grille));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkReadAccess(1L);

            assertThatThrownBy(() -> grilleService.listerItemsFeuilles(1L))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }
}
