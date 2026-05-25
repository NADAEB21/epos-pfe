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
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
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
                    .andExpect(jsonPath("$[0].id").value(1))
                    .andExpect(jsonPath("$[0].score_final").value(17.5))
                    .andExpect(jsonPath("$[0].verouillee").value(false));

            verify(service, times(1)).findAll();
        }

        @Test
        @DisplayName("200 - Retourne une liste vide")
        void getAll_devraitRetourner200AvecListeVide() throws Exception {
            when(service.findAll()).thenReturn(List.of());

            mockMvc.perform(get("/api/notations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
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
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.score_final").value(17.5));
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
                    .andExpect(jsonPath("$.id").value(1));
        }

        @Test
        @DisplayName("404 - Aucune notation pour cet assignment")
        void getByAssignment_devraitRetourner404() throws Exception {
            when(service.findByAssignment(99L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/notations/assignment/99"))
                    .andExpect(status().isNotFound());
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
                    .andExpect(jsonPath("$[0].id").value(1))
                    .andExpect(jsonPath("$[0].stationId").value(7))
                    .andExpect(jsonPath("$[0].score_final").value(17.5));

            verify(service, times(1)).findByStation(7L);
        }

        @Test
        @DisplayName("200 - Liste vide si aucune notation pour cette station")
        void getByStation_devraitRetournerListeVide() throws Exception {
            when(service.findByStation(99L)).thenReturn(List.of());

            mockMvc.perform(get("/api/notations/station/99"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }
    }

    // ─── GET /api/notations/grille/{grilleId} ─────────────────────────────────

    @Nested
    @DisplayName("GET /api/notations/grille/{grilleId}")
    class GetByGrille {

        @Test
        @DisplayName("200 - Retourne les notations d'une grille")
        void getByGrille_devraitRetourner200() throws Exception {
            when(service.findByGrille(11L)).thenReturn(List.of(notation));

            mockMvc.perform(get("/api/notations/grille/11"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(1))
                    .andExpect(jsonPath("$[0].grilleId").value(11));

            verify(service, times(1)).findByGrille(11L);
        }

        @Test
        @DisplayName("200 - Liste vide si aucune notation pour cette grille")
        void getByGrille_devraitRetournerListeVide() throws Exception {
            when(service.findByGrille(99L)).thenReturn(List.of());

            mockMvc.perform(get("/api/notations/grille/99"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }
    }

    // ─── POST /api/notations ──────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/notations")
    class Create {

        @Test
        @DisplayName("200 - Notation créée avec succès")
        void create_devraitRetourner200() throws Exception {
            when(service.save(any(Notation.class))).thenReturn(notation);

            mockMvc.perform(post("/api/notations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(notation)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.score_final").value(17.5))
                    .andExpect(jsonPath("$.verouillee").value(false));

            verify(service, times(1)).save(any(Notation.class));
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
            updated.setVerouillee(false);
            updated.setTemps_additionnel(5);

            when(service.update(eq(1L), any(Notation.class))).thenReturn(updated);

            mockMvc.perform(put("/api/notations/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updated)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.score_final").value(20.0))
                    .andExpect(jsonPath("$.is_synced").value(true));
        }

        @Test
        @DisplayName("400 - Modification d'une notation verrouillée")
        void update_notationVerrouillee_devraitRetourner400() throws Exception {
            when(service.update(eq(1L), any(Notation.class)))
                    .thenThrow(new BusinessException("Impossible de modifier une notation verrouillée."));

            mockMvc.perform(put("/api/notations/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(notation)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("404 - Notation introuvable à la mise à jour")
        void update_introuvable_devraitRetourner404() throws Exception {
            // Pre-#63 this returned 400 because the controller catch mapped every
            // RuntimeException to badRequest. Now ResourceNotFoundException → 404.
            when(service.update(eq(99L), any(Notation.class)))
                    .thenThrow(new ResourceNotFoundException("Notation non trouvée avec l'id : 99"));

            mockMvc.perform(put("/api/notations/99")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(notation)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("400 - Update échoue à cause d'une erreur métier")
        void update_shouldReturnBadRequest_whenServiceThrowsException() throws Exception {
            when(service.update(anyLong(), any(Notation.class)))
                    .thenThrow(new BusinessException("Erreur de validation"));

            mockMvc.perform(put("/api/notations/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(notation)))
                    .andExpect(status().isBadRequest());
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
                    .andExpect(jsonPath("$.verouillee").value(true));

            verify(service, times(1)).verrouiller(1L);
        }

        @Test
        @DisplayName("404 - Notation introuvable lors du verrouillage")
        void verrouiller_introuvable_devraitRetourner404() throws Exception {
            when(service.verrouiller(99L))
                    .thenThrow(new ResourceNotFoundException("Notation non trouvée avec l'id : 99"));

            mockMvc.perform(patch("/api/notations/99/verrouiller"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("404 - Verrouillage échoue (ID inconnu ou erreur service)")
        void lock_shouldReturnNotFound_whenServiceThrowsException() throws Exception {
            when(service.verrouiller(99L)).thenThrow(new ResourceNotFoundException("Erreur de verrouillage"));

            mockMvc.perform(patch("/api/notations/99/verrouiller"))
                    .andExpect(status().isNotFound());
        }
    }

    // ─── DELETE /api/notations/{id} ───────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/notations/{id}")
    class Delete {

        @Test
        @DisplayName("204 - Notation supprimée")
        void delete_devraitRetourner204() throws Exception {
            doNothing().when(service).delete(1L);

            mockMvc.perform(delete("/api/notations/1"))
                    .andExpect(status().isNoContent());

            verify(service, times(1)).delete(1L);
        }
    }
}
