package tn.epos.exam_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.exam_service.controllers.StationController;
import tn.epos.exam_service.dto.request.StationRequest;
import tn.epos.exam_service.dto.response.StationResponse;
import tn.epos.exam_service.enums.TypeStation;
import tn.epos.exam_service.exception.BusinessException;
import tn.epos.exam_service.exception.ResourceNotFoundException;
import tn.epos.exam_service.services.StationService;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StationController.class)
@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:application-test.properties") // <--- FORCE le chargement du fichier
@AutoConfigureMockMvc(addFilters = false)
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

    // ================================================================
    // POST /api/v1/examens/{examenId}/stations
    // ================================================================

    @Nested
    @DisplayName("POST /api/v1/examens/{examenId}/stations")
    class Ajouter {

        @Test
        @DisplayName("Doit retourner 201 avec la station créée")
        void ajouter_devraitRetourner201() throws Exception {
            when(stationService.ajouter(eq(1L), any(StationRequest.class)))
                    .thenReturn(stationResponse);

            mockMvc.perform(post("/api/v1/examens/1/stations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stationRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.nom").value("Station 3"))
                    .andExpect(jsonPath("$.data.type").value("PRATIQUE"));
        }

        @Test
        @DisplayName("Doit retourner 400 si nom manquant")
        void ajouter_devraitRetourner400SiNomManquant() throws Exception {
            stationRequest.setNom("");

            mockMvc.perform(post("/api/v1/examens/1/stations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stationRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Doit retourner 400 si type manquant")
        void ajouter_devraitRetourner400SiTypeManquant() throws Exception {
            stationRequest.setType(null);

            mockMvc.perform(post("/api/v1/examens/1/stations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stationRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Doit retourner 404 si examen introuvable")
        void ajouter_devraitRetourner404SiExamenIntrouvable() throws Exception {
            when(stationService.ajouter(eq(99L), any()))
                    .thenThrow(new ResourceNotFoundException("Examen", 99L));

            mockMvc.perform(post("/api/v1/examens/99/stations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stationRequest)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Doit retourner 400 si doublon de nom")
        void ajouter_devraitRetourner400SiDoublon() throws Exception {
            when(stationService.ajouter(eq(1L), any()))
                    .thenThrow(new BusinessException("Station déjà existante"));

            mockMvc.perform(post("/api/v1/examens/1/stations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stationRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ================================================================
    // GET /api/v1/examens/{examenId}/stations
    // ================================================================

    @Nested
    @DisplayName("GET /api/v1/examens/{examenId}/stations")
    class Lister {

        @Test
        @DisplayName("Doit retourner 200 avec la liste des stations")
        void lister_devraitRetourner200() throws Exception {
            when(stationService.listerParExamen(1L)).thenReturn(List.of(stationResponse));

            mockMvc.perform(get("/api/v1/examens/1/stations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].nom").value("Station 3"));
        }

        @Test
        @DisplayName("Doit retourner liste vide si aucune station")
        void lister_devraitRetournerListeVide() throws Exception {
            when(stationService.listerParExamen(1L)).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/examens/1/stations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("Doit retourner 404 si examen introuvable")
        void lister_devraitRetourner404SiExamenIntrouvable() throws Exception {
            when(stationService.listerParExamen(99L))
                    .thenThrow(new ResourceNotFoundException("Examen", 99L));

            mockMvc.perform(get("/api/v1/examens/99/stations"))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // GET /api/v1/stations/{id}
    // ================================================================

    @Nested
    @DisplayName("GET /api/v1/stations/{id}")
    class TrouverParId {

        @Test
        @DisplayName("Doit retourner 200 avec la station")
        void trouverParId_devraitRetourner200() throws Exception {
            when(stationService.trouverParId(1L)).thenReturn(stationResponse);

            mockMvc.perform(get("/api/v1/stations/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.nom").value("Station 3"));
        }

        @Test
        @DisplayName("Doit retourner 404 si station introuvable")
        void trouverParId_devraitRetourner404() throws Exception {
            when(stationService.trouverParId(99L))
                    .thenThrow(new ResourceNotFoundException("Station", 99L));

            mockMvc.perform(get("/api/v1/stations/99"))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // PUT /api/v1/stations/{id}
    // ================================================================

    @Nested
    @DisplayName("PUT /api/v1/stations/{id}")
    class Modifier {

        @Test
        @DisplayName("Doit retourner 200 avec la station modifiée")
        void modifier_devraitRetourner200() throws Exception {
            when(stationService.modifier(eq(1L), any(StationRequest.class)))
                    .thenReturn(stationResponse);

            mockMvc.perform(put("/api/v1/stations/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stationRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("Doit retourner 400 si modification interdite")
        void modifier_devraitRetourner400SiInterdit() throws Exception {
            when(stationService.modifier(eq(1L), any()))
                    .thenThrow(new BusinessException("Modification interdite"));

            mockMvc.perform(put("/api/v1/stations/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stationRequest)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ================================================================
    // DELETE /api/v1/stations/{id}
    // ================================================================

    @Nested
    @DisplayName("DELETE /api/v1/stations/{id}")
    class Supprimer {

        @Test
        @DisplayName("Doit retourner 200 après suppression")
        void supprimer_devraitRetourner200() throws Exception {
            doNothing().when(stationService).supprimer(1L);

            mockMvc.perform(delete("/api/v1/stations/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("Doit retourner 400 si suppression interdite")
        void supprimer_devraitRetourner400SiInterdit() throws Exception {
            doThrow(new BusinessException("Suppression interdite"))
                    .when(stationService).supprimer(1L);

            mockMvc.perform(delete("/api/v1/stations/1"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Doit retourner 404 si station introuvable")
        void supprimer_devraitRetourner404SiIntrouvable() throws Exception {
            doThrow(new ResourceNotFoundException("Station", 99L))
                    .when(stationService).supprimer(99L);

            mockMvc.perform(delete("/api/v1/stations/99"))
                    .andExpect(status().isNotFound());
        }
    }
}
