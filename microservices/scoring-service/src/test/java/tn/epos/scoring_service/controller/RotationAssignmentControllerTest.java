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
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationAssignment;
import tn.epos.scoring_service.entities.RotationStatus;
import tn.epos.scoring_service.service.RotationAssignmentService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RotationAssignmentController.class)
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(TestSecurityConfig.class)
@DisplayName("RotationAssignmentController - Tests unitaires")
class RotationAssignmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RotationAssignmentService service;

    private ObjectMapper objectMapper;
    private RotationAssignment assignment;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Rotation rotation = new Rotation();
        rotation.setId(1L);
        rotation.setStatut(RotationStatus.EN_ATTENTE);
        rotation.setOrdrePassage(1);
        rotation.setDebutCreneau(LocalDateTime.of(2024, 6, 15, 9, 0));

        ExamenParticipation participation = new ExamenParticipation();
        participation.setId(1L);
        participation.setExamen_id(10L);

        assignment = new RotationAssignment();
        assignment.setId(1L);
        assignment.setPresenceConfirmee(false);
        assignment.setTempsAdditionnel(0);
        assignment.setRotation(rotation);
        assignment.setParticipation(participation);
    }

    // ─── GET /api/assignments ─────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/assignments")
    class GetAll {

        @Test
        @DisplayName("200 - Retourne tous les assignments")
        void getAll_devraitRetourner200AvecListe() throws Exception {
            when(service.findAll()).thenReturn(List.of(assignment));

            mockMvc.perform(get("/api/assignments"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].presenceConfirmee").value(false));

            verify(service, times(1)).findAll();
        }

        @Test
        @DisplayName("200 - Retourne une liste vide")
        void getAll_devraitRetourner200AvecListeVide() throws Exception {
            when(service.findAll()).thenReturn(List.of());

            mockMvc.perform(get("/api/assignments"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    // ─── GET /api/assignments/{id} ────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/assignments/{id}")
    class GetById {

        @Test
        @DisplayName("200 - Assignment trouvé")
        void getById_devraitRetourner200() throws Exception {
            when(service.findById(1L)).thenReturn(Optional.of(assignment));

            mockMvc.perform(get("/api/assignments/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.presenceConfirmee").value(false));
        }

        @Test
        @DisplayName("404 - Assignment introuvable")
        void getById_devraitRetourner404() throws Exception {
            when(service.findById(99L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/assignments/99"))
                    .andExpect(status().isNotFound());
        }
    }

    // ─── GET /api/assignments/rotation/{rotationId} ───────────────────────────

    @Nested
    @DisplayName("GET /api/assignments/rotation/{rotationId}")
    class GetByRotation {

        @Test
        @DisplayName("200 - Retourne les assignments d'une rotation")
        void getByRotation_devraitRetourner200() throws Exception {
            when(service.findByRotation(1L)).thenReturn(List.of(assignment));

            mockMvc.perform(get("/api/assignments/rotation/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(1));
        }
    }

    // ─── POST /api/assignments ────────────────────────────────────────────────

    // =========================================================================
    // POST / PUT / DELETE /api/assignments et PATCH /{id}/presence — SUPPRIMES (#218).
    // La presence a son acte, borne a la matiere : PATCH /api/lots/{lotId}/presence.
    // =========================================================================
    @Nested
    @DisplayName("Ecritures brutes — SUPPRIMEES")
    class EcrituresSupprimees {

        @Test
        @DisplayName("POST /api/assignments n'existe plus")
        void postSupprime() throws Exception {
            mockMvc.perform(post("/api/assignments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        @DisplayName("PUT /api/assignments/{id} n'existe plus")
        void putSupprime() throws Exception {
            mockMvc.perform(put("/api/assignments/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        @DisplayName("DELETE /api/assignments/{id} n'existe plus")
        void deleteSupprime() throws Exception {
            mockMvc.perform(delete("/api/assignments/1"))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        @DisplayName("PATCH /{id}/presence n'existe plus — un evaluateur ne pointe plus chez un collegue")
        void presenceSupprimee() throws Exception {
            mockMvc.perform(patch("/api/assignments/1/presence").param("present", "false"))
                    .andExpect(status().isNotFound());
        }
    }
}