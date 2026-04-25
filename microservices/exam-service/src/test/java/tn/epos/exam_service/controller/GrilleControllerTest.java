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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.exam_service.controllers.GrilleController;
import tn.epos.exam_service.dto.request.GrilleRequest;
import tn.epos.exam_service.dto.request.ItemRequest;
import tn.epos.exam_service.dto.response.GrilleResponse;
import tn.epos.exam_service.dto.response.ItemResponse;
import tn.epos.exam_service.enums.TypeItem;
import tn.epos.exam_service.exception.BusinessException;
import tn.epos.exam_service.exception.ResourceNotFoundException;
import tn.epos.exam_service.services.GrilleService;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GrilleController.class)
@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:application-test.properties") // <--- FORCE le chargement du fichier
@DisplayName("GrilleController - Tests unitaires")
class GrilleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GrilleService grilleService;

    private ObjectMapper objectMapper;
    private GrilleResponse grilleResponse;
    private GrilleRequest grilleRequest;
    private ItemResponse itemResponse;
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

        itemResponse = new ItemResponse();
        itemResponse.setId(1L);
        itemResponse.setLibelle("Choix de l'indicateur");
        itemResponse.setType(TypeItem.BINAIRE);
        itemResponse.setPonderation(2.0);
        itemResponse.setOrdre(1);
        itemResponse.setGrilleId(1L);

        itemBinaireRequest = new ItemRequest();
        itemBinaireRequest.setLibelle("Choix de l'indicateur");
        itemBinaireRequest.setType(TypeItem.BINAIRE);
        itemBinaireRequest.setPonderation(2.0);

        itemNumeriqueRequest = new ItemRequest();
        itemNumeriqueRequest.setLibelle("Calcul de la masse");
        itemNumeriqueRequest.setType(TypeItem.NUMERIQUE);
        itemNumeriqueRequest.setPonderation(6.0);
        itemNumeriqueRequest.setValeurMax(6.0);
    }

    // ================================================================
    // POST /api/v1/stations/{stationId}/grille
    // ================================================================

    @Nested
    @DisplayName("POST /api/v1/stations/{stationId}/grille")
    class CreerGrille {

        @Test
        @DisplayName("Doit retourner 201 avec la grille créée")
        void creer_devraitRetourner201() throws Exception {
            when(grilleService.creerPourStation(eq(1L), any(GrilleRequest.class)))
                    .thenReturn(grilleResponse);

            mockMvc.perform(post("/api/v1/stations/1/grille")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(grilleRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.nom").value("Grille Station 3"))
                    .andExpect(jsonPath("$.data.noteMax").value(20.0));
        }

        @Test
        @DisplayName("Doit retourner 400 si nom manquant")
        void creer_devraitRetourner400SiNomManquant() throws Exception {
            grilleRequest.setNom("");

            mockMvc.perform(post("/api/v1/stations/1/grille")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(grilleRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Doit retourner 400 si grille déjà existante")
        void creer_devraitRetourner400SiGrilleExistante() throws Exception {
            when(grilleService.creerPourStation(eq(1L), any()))
                    .thenThrow(new BusinessException("Grille déjà existante"));

            mockMvc.perform(post("/api/v1/stations/1/grille")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(grilleRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("Doit retourner 404 si station introuvable")
        void creer_devraitRetourner404SiStationIntrouvable() throws Exception {
            when(grilleService.creerPourStation(eq(99L), any()))
                    .thenThrow(new ResourceNotFoundException("Station", 99L));

            mockMvc.perform(post("/api/v1/stations/99/grille")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(grilleRequest)))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // GET /api/v1/stations/{stationId}/grille
    // ================================================================

    @Nested
    @DisplayName("GET /api/v1/stations/{stationId}/grille")
    class TrouverParStation {

        @Test
        @DisplayName("Doit retourner 200 avec la grille")
        void trouverParStation_devraitRetourner200() throws Exception {
            when(grilleService.trouverParStation(1L)).thenReturn(grilleResponse);

            mockMvc.perform(get("/api/v1/stations/1/grille"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.noteMax").value(20.0));
        }

        @Test
        @DisplayName("Doit retourner 404 si aucune grille")
        void trouverParStation_devraitRetourner404() throws Exception {
            when(grilleService.trouverParStation(99L))
                    .thenThrow(new ResourceNotFoundException("Grille introuvable"));

            mockMvc.perform(get("/api/v1/stations/99/grille"))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // GET /api/v1/grilles/{id}
    // ================================================================

    @Nested
    @DisplayName("GET /api/v1/grilles/{id}")
    class TrouverParId {

        @Test
        @DisplayName("Doit retourner 200 avec la grille")
        void trouverParId_devraitRetourner200() throws Exception {
            when(grilleService.trouverParId(1L)).thenReturn(grilleResponse);

            mockMvc.perform(get("/api/v1/grilles/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1));
        }

        @Test
        @DisplayName("Doit retourner 404 si grille introuvable")
        void trouverParId_devraitRetourner404() throws Exception {
            when(grilleService.trouverParId(99L))
                    .thenThrow(new ResourceNotFoundException("Grille", 99L));

            mockMvc.perform(get("/api/v1/grilles/99"))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // PUT /api/v1/grilles/{id}
    // ================================================================

    @Nested
    @DisplayName("PUT /api/v1/grilles/{id}")
    class ModifierGrille {

        @Test
        @DisplayName("Doit retourner 200 avec la grille modifiée")
        void modifier_devraitRetourner200() throws Exception {
            when(grilleService.modifier(eq(1L), any(GrilleRequest.class)))
                    .thenReturn(grilleResponse);

            mockMvc.perform(put("/api/v1/grilles/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(grilleRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("Doit retourner 400 si modification interdite")
        void modifier_devraitRetourner400SiInterdit() throws Exception {
            when(grilleService.modifier(eq(1L), any()))
                    .thenThrow(new BusinessException("Modification interdite"));

            mockMvc.perform(put("/api/v1/grilles/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(grilleRequest)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ================================================================
    // DELETE /api/v1/grilles/{id}
    // ================================================================

    @Nested
    @DisplayName("DELETE /api/v1/grilles/{id}")
    class SupprimerGrille {

        @Test
        @DisplayName("Doit retourner 200 après suppression")
        void supprimer_devraitRetourner200() throws Exception {
            doNothing().when(grilleService).supprimer(1L);

            mockMvc.perform(delete("/api/v1/grilles/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("Doit retourner 400 si suppression interdite")
        void supprimer_devraitRetourner400SiInterdit() throws Exception {
            doThrow(new BusinessException("Suppression interdite"))
                    .when(grilleService).supprimer(1L);

            mockMvc.perform(delete("/api/v1/grilles/1"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ================================================================
    // POST /api/v1/grilles/{grilleId}/items
    // ================================================================

    @Nested
    @DisplayName("POST /api/v1/grilles/{grilleId}/items")
    class AjouterItem {

        @Test
        @DisplayName("Doit retourner 201 avec l'item BINAIRE créé")
        void ajouterItem_binaire_devraitRetourner201() throws Exception {
            when(grilleService.ajouterItem(eq(1L), any(ItemRequest.class)))
                    .thenReturn(itemResponse);

            mockMvc.perform(post("/api/v1/grilles/1/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemBinaireRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.type").value("BINAIRE"))
                    .andExpect(jsonPath("$.data.ponderation").value(2.0));
        }

        @Test
        @DisplayName("Doit retourner 201 avec l'item NUMERIQUE créé")
        void ajouterItem_numerique_devraitRetourner201() throws Exception {
            itemResponse.setType(TypeItem.NUMERIQUE);
            itemResponse.setValeurMax(6.0);
            when(grilleService.ajouterItem(eq(1L), any()))
                    .thenReturn(itemResponse);

            mockMvc.perform(post("/api/v1/grilles/1/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemNumeriqueRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.valeurMax").value(6.0));
        }

        @Test
        @DisplayName("Doit retourner 400 si libellé manquant")
        void ajouterItem_devraitRetourner400SiLibelleManquant() throws Exception {
            itemBinaireRequest.setLibelle("");

            mockMvc.perform(post("/api/v1/grilles/1/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemBinaireRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Doit retourner 400 si pondération dépasse noteMax")
        void ajouterItem_devraitRetourner400SiPonderationDepasse() throws Exception {
            when(grilleService.ajouterItem(eq(1L), any()))
                    .thenThrow(new BusinessException("Pondération dépasse noteMax"));

            mockMvc.perform(post("/api/v1/grilles/1/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemBinaireRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("Doit retourner 404 si grille introuvable")
        void ajouterItem_devraitRetourner404SiGrilleIntrouvable() throws Exception {
            when(grilleService.ajouterItem(eq(99L), any()))
                    .thenThrow(new ResourceNotFoundException("Grille", 99L));

            mockMvc.perform(post("/api/v1/grilles/99/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemBinaireRequest)))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // GET /api/v1/grilles/{grilleId}/items
    // ================================================================

    @Nested
    @DisplayName("GET /api/v1/grilles/{grilleId}/items")
    class ListerItems {

        @Test
        @DisplayName("Doit retourner 200 avec la liste des items")
        void listerItems_devraitRetourner200() throws Exception {
            when(grilleService.listerItems(1L)).thenReturn(List.of(itemResponse));

            mockMvc.perform(get("/api/v1/grilles/1/items"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].libelle").value("Choix de l'indicateur"));
        }

        @Test
        @DisplayName("Doit retourner liste vide si aucun item")
        void listerItems_devraitRetournerListeVide() throws Exception {
            when(grilleService.listerItems(1L)).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/grilles/1/items"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("Doit retourner 404 si grille introuvable")
        void listerItems_devraitRetourner404SiGrilleIntrouvable() throws Exception {
            when(grilleService.listerItems(99L))
                    .thenThrow(new ResourceNotFoundException("Grille", 99L));

            mockMvc.perform(get("/api/v1/grilles/99/items"))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // PUT /api/v1/items/{id}
    // ================================================================

    @Nested
    @DisplayName("PUT /api/v1/items/{id}")
    class ModifierItem {

        @Test
        @DisplayName("Doit retourner 200 avec l'item modifié")
        void modifierItem_devraitRetourner200() throws Exception {
            when(grilleService.modifierItem(eq(1L), any(ItemRequest.class)))
                    .thenReturn(itemResponse);

            mockMvc.perform(put("/api/v1/items/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemBinaireRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(1));
        }

        @Test
        @DisplayName("Doit retourner 404 si item introuvable")
        void modifierItem_devraitRetourner404() throws Exception {
            when(grilleService.modifierItem(eq(99L), any()))
                    .thenThrow(new ResourceNotFoundException("Item", 99L));

            mockMvc.perform(put("/api/v1/items/99")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemBinaireRequest)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Doit retourner 400 si modification interdite")
        void modifierItem_devraitRetourner400SiInterdit() throws Exception {
            when(grilleService.modifierItem(eq(1L), any()))
                    .thenThrow(new BusinessException("Modification interdite"));

            mockMvc.perform(put("/api/v1/items/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(itemBinaireRequest)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ================================================================
    // DELETE /api/v1/items/{id}
    // ================================================================

    @Nested
    @DisplayName("DELETE /api/v1/items/{id}")
    class SupprimerItem {

        @Test
        @DisplayName("Doit retourner 200 après suppression")
        void supprimerItem_devraitRetourner200() throws Exception {
            doNothing().when(grilleService).supprimerItem(1L);

            mockMvc.perform(delete("/api/v1/items/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("Doit retourner 404 si item introuvable")
        void supprimerItem_devraitRetourner404() throws Exception {
            doThrow(new ResourceNotFoundException("Item", 99L))
                    .when(grilleService).supprimerItem(99L);

            mockMvc.perform(delete("/api/v1/items/99"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Doit retourner 400 si suppression interdite")
        void supprimerItem_devraitRetourner400SiInterdit() throws Exception {
            doThrow(new BusinessException("Suppression interdite"))
                    .when(grilleService).supprimerItem(1L);

            mockMvc.perform(delete("/api/v1/items/1"))
                    .andExpect(status().isBadRequest());
        }
    }
}
