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
import tn.epos.exam_service.controllers.StationController;
import tn.epos.exam_service.dto.request.StationRequest;
import tn.epos.exam_service.dto.response.StationResponse;
import tn.epos.exam_service.enums.TypeStation;
import tn.epos.common.exception.BusinessException;
import tn.epos.exam_service.exception.GlobalExceptionHandler;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.exam_service.services.StationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import static org.mockito.ArgumentMatchers.eq;
import org.springframework.context.annotation.Import;
import tn.epos.exam_service.config.TestSecurityConfig;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {StationController.class, GlobalExceptionHandler.class})
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(TestSecurityConfig.class)
@DisplayName("StationController - Tests unitaires")
class StationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StationService stationService;

    private ObjectMapper objectMapper;
    private StationResponse stationResponse;
    private StationRequest stationRequest;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        stationResponse = new StationResponse();
        stationResponse.setId(1L);
        stationResponse.setNom("Station 3");
        stationResponse.setType(TypeStation.PRATIQUE);
        stationResponse.setOrdre(1);
        stationResponse.setExamenId(1L);
        stationResponse.setHasGrille(false);

        stationRequest = new StationRequest();
        stationRequest.setNom("Station 3");
        stationRequest.setType(TypeStation.PRATIQUE);
        stationRequest.setDescription("Description station");
    }

    // POST /api/examens/{examenId}/stations

    @Nested
    @DisplayName("POST /api/examens/{examenId}/stations")
    class Ajouter {

        @Test
        @DisplayName("201 - Station ajoutée avec succès")
        void ajouter_devraitRetourner201() throws Exception {
            when(stationService.ajouter(eq(1L), any(StationRequest.class)))
                    .thenReturn(stationResponse);

            mockMvc.perform(post("/api/examens/1/stations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stationRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.nom").value("Station 3"))
                    .andExpect(jsonPath("$.data.type").value("PRATIQUE"))
                    .andExpect(jsonPath("$.data.ordre").value(1));

            verify(stationService, times(1)).ajouter(eq(1L), any());
        }

        @Test
        @DisplayName("400 - Nom vide")
        void ajouter_nomVide_devraitRetourner400() throws Exception {
            stationRequest.setNom("");

            mockMvc.perform(post("/api/examens/1/stations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stationRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 - Type manquant")
        void ajouter_typeManquant_devraitRetourner400() throws Exception {
            stationRequest.setType(null);

            mockMvc.perform(post("/api/examens/1/stations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stationRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 - Doublon de nom dans le même examen")
        void ajouter_doublon_devraitRetourner400() throws Exception {
            when(stationService.ajouter(eq(1L), any()))
                    .thenThrow(new BusinessException("Une station nommée 'Station 3' existe déjà"));

            mockMvc.perform(post("/api/examens/1/stations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stationRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("400 - Examen non modifiable (EN_COURS)")
        void ajouter_examenEnCours_devraitRetourner400() throws Exception {
            when(stationService.ajouter(eq(1L), any()))
                    .thenThrow(new BusinessException("L'examen est au statut EN_COURS"));

            mockMvc.perform(post("/api/examens/1/stations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stationRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("404 - Examen introuvable")
        void ajouter_examenIntrouvable_devraitRetourner404() throws Exception {
            when(stationService.ajouter(eq(99L), any()))
                    .thenThrow(new ResourceNotFoundException("Examen", 99L));

            mockMvc.perform(post("/api/examens/99/stations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stationRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // GET /api/examens/{examenId}/stations

    @Nested
    @DisplayName("GET /api/examens/{examenId}/stations")
    class Lister {

        @Test
        @DisplayName("200 - Liste des stations retournée")
        void lister_devraitRetourner200() throws Exception {
            Page<StationResponse> page = new PageImpl<>(List.of(stationResponse));
            when(stationService.listerParExamen(eq(1L), any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/examens/1/stations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content[0].nom").value("Station 3"))
                    .andExpect(jsonPath("$.data.content[0].examenId").value(1));
        }

        @Test
        @DisplayName("200 - Liste vide si aucune station")
        void lister_listeVide_devraitRetourner200() throws Exception {
            Page<StationResponse> page = new PageImpl<>(List.of());
            when(stationService.listerParExamen(eq(1L), any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/examens/1/stations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isEmpty())
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }

        @Test
        @DisplayName("404 - Examen introuvable")
        void lister_examenIntrouvable_devraitRetourner404() throws Exception {
            when(stationService.listerParExamen(eq(99L), any(Pageable.class)))
                    .thenThrow(new ResourceNotFoundException("Examen", 99L));

            mockMvc.perform(get("/api/examens/99/stations"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("200 - Plusieurs stations retournées")
        void lister_plusieursStations_devraitRetournerToutes() throws Exception {
            StationResponse station2 = new StationResponse();
            station2.setId(2L);
            station2.setNom("Station 4");
            station2.setType(TypeStation.THEORIQUE);
            station2.setOrdre(2);
            station2.setExamenId(1L);

            Page<StationResponse> page = new PageImpl<>(List.of(stationResponse, station2));
            when(stationService.listerParExamen(eq(1L), any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/examens/1/stations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content.length()").value(2))
                    .andExpect(jsonPath("$.data.content[1].nom").value("Station 4"));
        }
    }

    // GET /api/stations/{id}

    @Nested
    @DisplayName("GET /api/stations/{id}")
    class TrouverParId {

        @Test
        @DisplayName("200 - Station trouvée")
        void trouverParId_devraitRetourner200() throws Exception {
            when(stationService.trouverParId(1L)).thenReturn(stationResponse);

            mockMvc.perform(get("/api/stations/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.nom").value("Station 3"))
                    .andExpect(jsonPath("$.data.type").value("PRATIQUE"));
        }

        @Test
        @DisplayName("200 - Station avec grille")
        void trouverParId_avecGrille_devraitRetourner200() throws Exception {
            stationResponse.setHasGrille(true);
            when(stationService.trouverParId(1L)).thenReturn(stationResponse);

            mockMvc.perform(get("/api/stations/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hasGrille").value(true));
        }

        @Test
        @DisplayName("404 - Station introuvable")
        void trouverParId_introuvable_devraitRetourner404() throws Exception {
            when(stationService.trouverParId(99L))
                    .thenThrow(new ResourceNotFoundException("Station", 99L));

            mockMvc.perform(get("/api/stations/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // PUT /api/stations/{id}

    @Nested
    @DisplayName("PUT /api/stations/{id}")
    class Modifier {

        @Test
        @DisplayName("200 - Station modifiée avec succès")
        void modifier_devraitRetourner200() throws Exception {
            stationResponse.setNom("Station 3 Modifiée");
            when(stationService.modifier(eq(1L), any(StationRequest.class)))
                    .thenReturn(stationResponse);

            mockMvc.perform(put("/api/stations/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stationRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Station modifiée avec succès"));
        }

        @Test
        @DisplayName("400 - Modification interdite (examen EN_COURS)")
        void modifier_examenEnCours_devraitRetourner400() throws Exception {
            when(stationService.modifier(eq(1L), any()))
                    .thenThrow(new BusinessException("L'examen est au statut EN_COURS"));

            mockMvc.perform(put("/api/stations/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stationRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("400 - Nom vide lors de la modification")
        void modifier_nomVide_devraitRetourner400() throws Exception {
            stationRequest.setNom("");

            mockMvc.perform(put("/api/stations/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stationRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("404 - Station introuvable")
        void modifier_introuvable_devraitRetourner404() throws Exception {
            when(stationService.modifier(eq(99L), any()))
                    .thenThrow(new ResourceNotFoundException("Station", 99L));

            mockMvc.perform(put("/api/stations/99")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stationRequest)))
                    .andExpect(status().isNotFound());
        }
    }

    // Affecter un evaluateur
    @Nested
    @DisplayName("PATCH /api/stations/{id}/evaluateurs")
    class AffecterEvaluateurs {

        @Test
        @DisplayName("200 - Évaluateurs affectés avec succès")
        void affecterEvaluateurs_devraitRetourner200() throws Exception {
            stationResponse.setEvaluateurIds(List.of(10L, 20L));
            when(stationService.affecterEvaluateurs(eq(1L), anyList()))
                    .thenReturn(stationResponse);

            mockMvc.perform(patch("/api/stations/1/evaluateurs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[10, 20]"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Évaluateurs affectés"))
                    .andExpect(jsonPath("$.data.evaluateurIds").isArray())
                    .andExpect(jsonPath("$.data.evaluateurIds[0]").value(10))
                    .andExpect(jsonPath("$.data.evaluateurIds[1]").value(20));

            verify(stationService, times(1)).affecterEvaluateurs(eq(1L), anyList());
        }

        @Test
        @DisplayName("200 - Liste vide retire tous les évaluateurs")
        void affecterEvaluateurs_listeVide_devraitRetirer() throws Exception {
            stationResponse.setEvaluateurIds(List.of());
            when(stationService.affecterEvaluateurs(eq(1L), anyList()))
                    .thenReturn(stationResponse);

            mockMvc.perform(patch("/api/stations/1/evaluateurs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[]"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.evaluateurIds").isEmpty());
        }

        @Test
        @DisplayName("400 - Examen non modifiable")
        void affecterEvaluateurs_examenVerrouille_devraitRetourner400() throws Exception {
            when(stationService.affecterEvaluateurs(eq(1L), anyList()))
                    .thenThrow(new BusinessException("Impossible de modifier les évaluateurs : l'examen est au statut EN_COURS"));

            mockMvc.perform(patch("/api/stations/1/evaluateurs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[10, 20]"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("404 - Station introuvable")
        void affecterEvaluateurs_stationIntrouvable_devraitRetourner404() throws Exception {
            when(stationService.affecterEvaluateurs(eq(99L), anyList()))
                    .thenThrow(new ResourceNotFoundException("Station", 99L));

            mockMvc.perform(patch("/api/stations/99/evaluateurs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[10]"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // DELETE /api/stations/{id}

    @Nested
    @DisplayName("DELETE /api/stations/{id}")
    class Supprimer {

        @Test
        @DisplayName("200 - Station supprimée avec succès")
        void supprimer_devraitRetourner200() throws Exception {
            doNothing().when(stationService).supprimer(1L);

            mockMvc.perform(delete("/api/stations/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Station supprimée avec succès"));

            verify(stationService, times(1)).supprimer(1L);
        }

        @Test
        @DisplayName("400 - Suppression interdite (examen EN_COURS)")
        void supprimer_examenEnCours_devraitRetourner400() throws Exception {
            doThrow(new BusinessException("L'examen est au statut EN_COURS"))
                    .when(stationService).supprimer(1L);

            mockMvc.perform(delete("/api/stations/1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("404 - Station introuvable")
        void supprimer_introuvable_devraitRetourner404() throws Exception {
            doThrow(new ResourceNotFoundException("Station", 99L))
                    .when(stationService).supprimer(99L);

            mockMvc.perform(delete("/api/stations/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }
}

