package tn.epos.scoring_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.scoring_service.config.TestSecurityConfig;
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.service.NotationService;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = NotationController.class)
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(TestSecurityConfig.class)
@DisplayName("NotationController - Tests unitaires")
class NotationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotationService service;

    private ObjectMapper objectMapper;
    private Notation notation;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        notation = new Notation();
        notation.setId(1L);
        notation.setScore_final(17.5f);
        notation.setTemps_additionnel(0);
        notation.setIs_synced(false);
        notation.setVerouillee(false);
        notation.setStationId(7L);
        notation.setGrilleId(11L);
    }

    // ─── GET /api/notations ───────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/notations")
    class GetAll {

        @Test
        @DisplayName("200 - Retourne toutes les notations")
        void getAll_devraitRetourner200AvecListe() throws Exception {
            when(service.findAll()).thenReturn(List.of(notation));

            mockMvc.perform(get("/api/notations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].score_final").value(17.5));

            verify(service, times(1)).findAll();
        }

        @Test
        @DisplayName("200 - Retourne une liste vide")
        void getAll_devraitRetourner200AvecListeVide() throws Exception {
            when(service.findAll()).thenReturn(List.of());

            mockMvc.perform(get("/api/notations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    // ─── GET /api/notations/{id} ──────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/notations/{id}")
    class GetById {

        @Test
        @DisplayName("200 - Notation trouvée")
        void getById_devraitRetourner200() throws Exception {
            when(service.findById(1L)).thenReturn(Optional.of(notation));

            mockMvc.perform(get("/api/notations/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.score_final").value(17.5));
        }

        @Test
        @DisplayName("404 - Notation introuvable")
        void getById_devraitRetourner404() throws Exception {
            when(service.findById(99L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/notations/99"))
                    .andExpect(status().isNotFound());
        }
    }

    // ─── GET /api/notations/assignment/{assignmentId} ─────────────────────────

    @Nested
    @DisplayName("GET /api/notations/assignment/{assignmentId}")
    class GetByAssignment {

        @Test
        @DisplayName("200 - Notation trouvée pour cet assignment")
        void getByAssignment_devraitRetourner200() throws Exception {
            when(service.findByAssignment(42L)).thenReturn(Optional.of(notation));

            mockMvc.perform(get("/api/notations/assignment/42"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1));
        }
    }

    // ─── GET /api/notations/station/{stationId} ───────────────────────────────

    @Nested
    @DisplayName("GET /api/notations/station/{stationId}")
    class GetByStation {

        @Test
        @DisplayName("200 - Retourne les notations d'une station")
        void getByStation_devraitRetourner200() throws Exception {
            when(service.findByStation(7L)).thenReturn(List.of(notation));

            mockMvc.perform(get("/api/notations/station/7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].stationId").value(7));
        }
    }

    // ─── POST /api/notations ──────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/notations")
    class Create {

        @Test
        @DisplayName("201 - Notation créée avec succès")
        void create_devraitRetourner200() throws Exception {
            when(service.save(any(Notation.class), any())).thenReturn(notation);

            mockMvc.perform(post("/api/notations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(notation)))
                    .andExpect(status().isCreated()) // Matches controller
                    .andExpect(jsonPath("$.data.score_final").value(17.5));
        }
    }

    // ─── PUT /api/notations/{id} ──────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/notations/{id}")
    class Update {

        @Test
        @DisplayName("200 - Notation mise à jour")
        void update_devraitRetourner200() throws Exception {
            Notation updated = new Notation();
            updated.setId(1L);
            updated.setScore_final(20.0f);
            updated.setIs_synced(true);

            when(service.update(eq(1L), any(Notation.class))).thenReturn(updated);

            mockMvc.perform(put("/api/notations/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updated)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.score_final").value(20.0));
        }
    }

    // ─── PATCH /api/notations/{id}/verrouiller ────────────────────────────────

    @Nested
    @DisplayName("PATCH /api/notations/{id}/verrouiller")
    class Verrouiller {

        @Test
        @DisplayName("200 - Notation verrouillée avec succès")
        void verrouiller_devraitRetourner200() throws Exception {
            notation.setVerouillee(true);
            when(service.verrouiller(1L)).thenReturn(notation);

            mockMvc.perform(patch("/api/notations/1/verrouiller"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.verouillee").value(true));
        }
    }

    // ─── DELETE /api/notations/{id} ───────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/notations/{id}")
    class Delete {

        @Test
        @DisplayName("200 - Notation supprimée") // Changed from 204 to 200
        void delete_devraitRetourner204() throws Exception {
            doNothing().when(service).delete(1L);

            mockMvc.perform(delete("/api/notations/1"))
                    .andExpect(status().isOk()) // Standardized to ApiResponse
                    .andExpect(jsonPath("$.success").value(true));

            verify(service, times(1)).delete(1L);
        }
    }
}