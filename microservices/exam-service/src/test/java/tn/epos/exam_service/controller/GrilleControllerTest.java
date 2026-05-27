package tn.epos.exam_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.exam_service.controllers.GrilleController;
import tn.epos.exam_service.dto.request.GrilleRequest;
import tn.epos.exam_service.dto.request.ItemRequest;
import tn.epos.exam_service.dto.response.GrilleResponse;
import tn.epos.exam_service.dto.response.ItemResponse;
import tn.epos.exam_service.enums.TypeItem;
import tn.epos.common.exception.BusinessException;
import tn.epos.exam_service.exception.GlobalExceptionHandler;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.exam_service.services.GrilleService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import static org.mockito.ArgumentMatchers.eq;
import org.springframework.context.annotation.Import;
import tn.epos.exam_service.config.TestSecurityConfig;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {GrilleController.class, GlobalExceptionHandler.class})
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(TestSecurityConfig.class)
@DisplayName("GrilleController - Tests unitaires")
class GrilleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GrilleService grilleService;

    private ObjectMapper objectMapper;
    private GrilleResponse grilleResponse;
    private GrilleRequest grilleRequest;
    private ItemResponse itemBinaireResponse;
    private ItemResponse itemNumeriqueResponse;
    private ItemRequest itemBinaireRequest;
    private ItemRequest itemNumeriqueRequest;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        grilleResponse = new GrilleResponse();
        grilleResponse.setId(1L);
        grilleResponse.setNom("Grille Station 3");
        grilleResponse.setNoteMax(20.0);
        grilleResponse.setStationId(1L);
        grilleResponse.setSommePonderations(0.0);
        grilleResponse.setPonderationValide(false);
        grilleResponse.setNombreItems(0);
        grilleResponse.setItems(new ArrayList<>());

        grilleRequest = new GrilleRequest();
        grilleRequest.setNom("Grille Station 3");
        grilleRequest.setNoteMax(20.0);
        grilleRequest.setItems(new ArrayList<>());

        itemBinaireResponse = new ItemResponse();
        itemBinaireResponse.setId(1L);
        itemBinaireResponse.setLibelle("Choix de l'indicateur coloré");
        itemBinaireResponse.setType(TypeItem.BINAIRE);
        itemBinaireResponse.setPonderation(2.0);
        itemBinaireResponse.setOrdre(1);
        itemBinaireResponse.setGrilleId(1L);

        itemNumeriqueResponse = new ItemResponse();
        itemNumeriqueResponse.setId(2L);
        itemNumeriqueResponse.setLibelle("Calcul de la masse");
        itemNumeriqueResponse.setType(TypeItem.NUMERIQUE);
        itemNumeriqueResponse.setPonderation(6.0);
        itemNumeriqueResponse.setValeurMax(6.0);
        itemNumeriqueResponse.setOrdre(2);
        itemNumeriqueResponse.setGrilleId(1L);

        itemBinaireRequest = new ItemRequest();
        itemBinaireRequest.setLibelle("Choix de l'indicateur coloré");
        itemBinaireRequest.setType(TypeItem.BINAIRE);
        itemBinaireRequest.setPonderation(2.0);

        itemNumeriqueRequest = new ItemRequest();
        itemNumeriqueRequest.setLibelle("Calcul de la masse");
        itemNumeriqueRequest.setType(TypeItem.NUMERIQUE);
        itemNumeriqueRequest.setPonderation(6.0);
        itemNumeriqueRequest.setValeurMax(6.0);
    }

    // POST /api/stations/{stationId}/grille

    @Nested
    @DisplayName("POST /api/stations/{stationId}/grille")
    class CreerGrille {

        @Test
        @DisplayName("201 - Grille créée avec succès")
        void creer_devraitRetourner201() throws Exception {
            when(grilleService.creerPourStation(eq(1L), any(GrilleRequest.class)))
                    .thenReturn(grilleResponse);

            mockMvc.perform(post("/api/stations/1/grille")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(grilleRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.nom").value("Grille Station 3"))
                    .andExpect(jsonPath("$.data.noteMax").value(20.0))
                    .andExpect(jsonPath("$.data.stationId").value(1));

            verify(grilleService, times(1)).creerPourStation(eq(1L), any());
        }

        @Test
        @DisplayName("201 - Grille créée avec items inclus")
        void creer_avecItems_devraitRetourner201() throws Exception {
            grilleRequest.setItems(List.of(itemBinaireRequest));
            grilleResponse.setNombreItems(1);
            grilleResponse.setSommePonderations(2.0);

            when(grilleService.creerPourStation(eq(1L), any()))
                    .thenReturn(grilleResponse);

            mockMvc.perform(post("/api/stations/1/grille")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(grilleRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.nombreItems").value(1))
                    .andExpect(jsonPath("$.data.sommePonderations").value(2.0));
        }

        @Test
        @DisplayName("400 - Nom vide")
        void creer_nomVide_devraitRetourner400() throws Exception {
            grilleRequest.setNom("");

            mockMvc.perform(post("/api/stations/1/grille")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(grilleRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 - Grille déjà existante pour cette station")
        void creer_grilleDejaExistante_devraitRetourner400() throws Exception {
            when(grilleService.creerPourStation(eq(1L), any()))
                    .thenThrow(new BusinessException("La station possède déjà une grille"));

            mockMvc.perform(post("/api/stations/1/grille")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(grilleRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("400 - Examen non modifiable")
        void creer_examenNonModifiable_devraitRetourner400() throws Exception {
            when(grilleService.creerPourStation(eq(1L), any()))
                    .thenThrow(new BusinessException("L'examen est au statut EN_COURS"));

            mockMvc.perform(post("/api/stations/1/grille")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(grilleRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("404 - Station introuvable")
        void creer_stationIntrouvable_devraitRetourner404() throws Exception {
            when(grilleService.creerPourStation(eq(99L), any()))
                    .thenThrow(new ResourceNotFoundException("Station", 99L));

            mockMvc.perform(post("/api/stations/99/grille")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(grilleRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // GET /api/stations/{stationId}/grille

    @Nested
    @DisplayName("GET /api/stations/{stationId}/grille")
    class TrouverParStation {

        @Test
        @DisplayName("200 - Grille trouvée avec ses items")
        void trouverParStation_devraitRetourner200() throws Exception {
            grilleResponse.setItems(List.of(itemBinaireResponse));
            grilleResponse.setNombreItems(1);
            when(grilleService.trouverParStation(1L)).thenReturn(grilleResponse);

            mockMvc.perform(get("/api/stations/1/grille"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.noteMax").value(20.0))
                    .andExpect(jsonPath("$.data.items").isArray());
        }

        @Test
        @DisplayName("404 - Aucune grille pour cette station")
        void trouverParStation_aucuneGrille_devraitRetourner404() throws Exception {
            when(grilleService.trouverParStation(99L))
                    .thenThrow(new ResourceNotFoundException("Aucune grille trouvée pour la station 99"));

            mockMvc.perform(get("/api/stations/99/grille"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // GET /api/grilles/{id}

    @Nested
    @DisplayName("GET /api/grilles/{id}")
    class TrouverParId {

        @Test
        @DisplayName("200 - Grille trouvée par ID")
        void trouverParId_devraitRetourner200() throws Exception {
            when(grilleService.trouverParId(1L)).thenReturn(grilleResponse);

            mockMvc.perform(get("/api/grilles/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.nom").value("Grille Station 3"));
        }

        @Test
        @DisplayName("404 - Grille introuvable")
        void trouverParId_introuvable_devraitRetourner404() throws Exception {
            when(grilleService.trouverParId(99L))
                    .thenThrow(new ResourceNotFoundException("Grille", 99L));

            mockMvc.perform(get("/api/grilles/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // PUT /api/grilles/{id}

    @Nested
    @DisplayName("PUT /api/grilles/{id}")
    class ModifierGrille {

        @Test
        @DisplayName("200 - Grille modifiée avec succès")
        void modifier_devraitRetourner200() throws Exception {
            grilleResponse.setNom("Grille Modifiée");
            when(grilleService.modifier(eq(1L), any(GrilleRequest.class)))
                    .thenReturn(grilleResponse);

            mockMvc.perform(put("/api/grilles/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(grilleRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Grille modifiée avec succès"));
        }

        @Test
        @DisplayName("400 - Modification interdite")
        void modifier_interdit_devraitRetourner400() throws Exception {
            when(grilleService.modifier(eq(1L), any()))
                    .thenThrow(new BusinessException("L'examen est au statut EN_COURS"));

            mockMvc.perform(put("/api/grilles/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(grilleRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("400 - Nom vide lors de la modification")
        void modifier_nomVide_devraitRetourner400() throws Exception {
            grilleRequest.setNom("");

            mockMvc.perform(put("/api/grilles/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(grilleRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("404 - Grille introuvable")
        void modifier_introuvable_devraitRetourner404() throws Exception {
            when(grilleService.modifier(eq(99L), any()))
                    .thenThrow(new ResourceNotFoundException("Grille", 99L));

            mockMvc.perform(put("/api/grilles/99")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(grilleRequest)))
                    .andExpect(status().isNotFound());
        }
    }

    // DELETE /api/grilles/{id}

    @Nested
    @DisplayName("DELETE /api/grilles/{id}")
    class SupprimerGrille {

        @Test
        @DisplayName("200 - Grille supprimée avec succès")
        void supprimer_devraitRetourner200() throws Exception {
            doNothing().when(grilleService).supprimer(1L);

            mockMvc.perform(delete("/api/grilles/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Grille supprimée avec succès"));

            verify(grilleService, times(1)).supprimer(1L);
        }

        @Test
        @DisplayName("400 - Suppression interdite")
        void supprimer_interdit_devraitRetourner400() throws Exception {
            doThrow(new BusinessException("L'examen est au statut EN_COURS"))
                    .when(grilleService).supprimer(1L);

            mockMvc.perform(delete("/api/grilles/1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("404 - Grille introuvable")
        void supprimer_introuvable_devraitRetourner404() throws Exception {
            doThrow(new ResourceNotFoundException("Grille", 99L))
                    .when(grilleService).supprimer(99L);

            mockMvc.perform(delete("/api/grilles/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // POST /api/grilles/{grilleId}/items

    @Nested
    @DisplayName("POST /api/grilles/{grilleId}/items")
    class AjouterItem {

        @Test
        @DisplayName("201 - Item BINAIRE ajouté avec succès")
        void ajouterItem_binaire_devraitRetourner201() throws Exception {
            when(grilleService.ajouterItem(eq(1L), any(ItemRequest.class)))
                    .thenReturn(itemBinaireResponse);

            mockMvc.perform(post("/api/grilles/1/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemBinaireRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.type").value("BINAIRE"))
                    .andExpect(jsonPath("$.data.ponderation").value(2.0))
                    .andExpect(jsonPath("$.data.ordre").value(1));

            verify(grilleService, times(1)).ajouterItem(eq(1L), any());
        }

        @Test
        @DisplayName("201 - Item NUMERIQUE ajouté avec succès")
        void ajouterItem_numerique_devraitRetourner201() throws Exception {
            when(grilleService.ajouterItem(eq(1L), any()))
                    .thenReturn(itemNumeriqueResponse);

            mockMvc.perform(post("/api/grilles/1/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemNumeriqueRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.type").value("NUMERIQUE"))
                    .andExpect(jsonPath("$.data.ponderation").value(6.0))
                    .andExpect(jsonPath("$.data.valeurMax").value(6.0));
        }

        @Test
        @DisplayName("400 - Libellé vide")
        void ajouterItem_libelleVide_devraitRetourner400() throws Exception {
            itemBinaireRequest.setLibelle("");

            mockMvc.perform(post("/api/grilles/1/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemBinaireRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 - Type manquant")
        void ajouterItem_typeManquant_devraitRetourner400() throws Exception {
            itemBinaireRequest.setType(null);

            mockMvc.perform(post("/api/grilles/1/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemBinaireRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 - Pondération dépasse noteMax")
        void ajouterItem_ponderationDepasse_devraitRetourner400() throws Exception {
            when(grilleService.ajouterItem(eq(1L), any()))
                    .thenThrow(new BusinessException("La somme des pondérations dépasserait la note maximale"));

            mockMvc.perform(post("/api/grilles/1/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemBinaireRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("400 - NUMERIQUE sans valeurMax")
        void ajouterItem_numeriqueSansValeurMax_devraitRetourner400() throws Exception {
            when(grilleService.ajouterItem(eq(1L), any()))
                    .thenThrow(new BusinessException("Un item NUMERIQUE doit avoir une valeurMax"));

            itemNumeriqueRequest.setValeurMax(null);

            mockMvc.perform(post("/api/grilles/1/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemNumeriqueRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 - Grille verrouillée (examen EN_COURS)")
        void ajouterItem_grilleVerrouillee_devraitRetourner400() throws Exception {
            when(grilleService.ajouterItem(eq(1L), any()))
                    .thenThrow(new BusinessException("L'examen est au statut EN_COURS"));

            mockMvc.perform(post("/api/grilles/1/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemBinaireRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("404 - Grille introuvable")
        void ajouterItem_grilleIntrouvable_devraitRetourner404() throws Exception {
            when(grilleService.ajouterItem(eq(99L), any()))
                    .thenThrow(new ResourceNotFoundException("Grille", 99L));

            mockMvc.perform(post("/api/grilles/99/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemBinaireRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // GET /api/grilles/{grilleId}/items

    @Nested
    @DisplayName("GET /api/grilles/{grilleId}/items")
    class ListerItems {

        @Test
        @DisplayName("200 - Liste des items retournée")
        void listerItems_devraitRetourner200() throws Exception {
            Page<ItemResponse> page = new PageImpl<>(List.of(itemBinaireResponse, itemNumeriqueResponse));
            when(grilleService.listerItems(eq(1L), any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/grilles/1/items"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content.length()").value(2))
                    .andExpect(jsonPath("$.data.content[0].type").value("BINAIRE"))
                    .andExpect(jsonPath("$.data.content[1].type").value("NUMERIQUE"));
        }

        @Test
        @DisplayName("200 - Liste vide si aucun item")
        void listerItems_listeVide_devraitRetourner200() throws Exception {
            Page<ItemResponse> page = new PageImpl<>(List.of());
            when(grilleService.listerItems(eq(1L), any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/grilles/1/items"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isEmpty())
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }

        @Test
        @DisplayName("404 - Grille introuvable")
        void listerItems_grilleIntrouvable_devraitRetourner404() throws Exception {
            when(grilleService.listerItems(eq(99L), any(Pageable.class)))
                    .thenThrow(new ResourceNotFoundException("Grille", 99L));

            mockMvc.perform(get("/api/grilles/99/items"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // PUT /api/items/{id}

    @Nested
    @DisplayName("PUT /api/items/{id}")
    class ModifierItem {

        @Test
        @DisplayName("200 - Item modifié avec succès")
        void modifierItem_devraitRetourner200() throws Exception {
            when(grilleService.modifierItem(eq(1L), any(ItemRequest.class)))
                    .thenReturn(itemBinaireResponse);

            mockMvc.perform(put("/api/items/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemBinaireRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Critère modifié avec succès"))
                    .andExpect(jsonPath("$.data.id").value(1));

            verify(grilleService, times(1)).modifierItem(eq(1L), any());
        }

        @Test
        @DisplayName("400 - Libellé vide")
        void modifierItem_libelleVide_devraitRetourner400() throws Exception {
            itemBinaireRequest.setLibelle("");

            mockMvc.perform(put("/api/items/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemBinaireRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 - Modification interdite (examen verrouillé)")
        void modifierItem_examenVerrouille_devraitRetourner400() throws Exception {
            when(grilleService.modifierItem(eq(1L), any()))
                    .thenThrow(new BusinessException("L'examen est au statut EN_COURS"));

            mockMvc.perform(put("/api/items/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemBinaireRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("400 - Pondération invalide après modification")
        void modifierItem_ponderationInvalide_devraitRetourner400() throws Exception {
            when(grilleService.modifierItem(eq(1L), any()))
                    .thenThrow(new BusinessException("La somme des pondérations dépasserait la note maximale"));

            mockMvc.perform(put("/api/items/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemBinaireRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("404 - Item introuvable")
        void modifierItem_introuvable_devraitRetourner404() throws Exception {
            when(grilleService.modifierItem(eq(99L), any()))
                    .thenThrow(new ResourceNotFoundException("Item", 99L));

            mockMvc.perform(put("/api/items/99")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemBinaireRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // DELETE /api/items/{id}

    @Nested
    @DisplayName("DELETE /api/items/{id}")
    class SupprimerItem {

        @Test
        @DisplayName("200 - Item supprimé avec succès")
        void supprimerItem_devraitRetourner200() throws Exception {
            doNothing().when(grilleService).supprimerItem(1L);

            mockMvc.perform(delete("/api/items/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Critère supprimé avec succès"));

            verify(grilleService, times(1)).supprimerItem(1L);
        }

        @Test
        @DisplayName("400 - Suppression interdite (examen verrouillé)")
        void supprimerItem_examenVerrouille_devraitRetourner400() throws Exception {
            doThrow(new BusinessException("L'examen est au statut EN_COURS"))
                    .when(grilleService).supprimerItem(1L);

            mockMvc.perform(delete("/api/items/1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("404 - Item introuvable")
        void supprimerItem_introuvable_devraitRetourner404() throws Exception {
            doThrow(new ResourceNotFoundException("Item", 99L))
                    .when(grilleService).supprimerItem(99L);

            mockMvc.perform(delete("/api/items/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }
}