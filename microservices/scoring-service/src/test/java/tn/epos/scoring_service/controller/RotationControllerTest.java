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
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationStatus;
import tn.epos.scoring_service.entities.StudentGroup;
import tn.epos.scoring_service.service.RotationService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = RotationController.class)
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(TestSecurityConfig.class)
@DisplayName("RotationController - Tests unitaires")
class RotationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RotationService rotationService;

    private ObjectMapper objectMapper;
    private Rotation rotation;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        StudentGroup group = new StudentGroup();
        group.setId(1L);
        group.setNumeroGroupe(1);

        rotation = new Rotation();
        rotation.setId(1L);
        rotation.setEvaluateurId(3L);
        rotation.setOrdrePassage(1);
        rotation.setDebutCreneau(LocalDateTime.of(2024, 6, 15, 9, 0));
        rotation.setStatut(RotationStatus.EN_ATTENTE);
        rotation.setStudentGroup(group);
    }

    // ─── GET /api/rotations ───────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/rotations")
    class GetAll {

        @Test
        @DisplayName("200 - Retourne toutes les rotations")
        void getAll_devraitRetourner200AvecListe() throws Exception {
            when(rotationService.findAll()).thenReturn(List.of(rotation));

            mockMvc.perform(get("/api/rotations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(1))
                    .andExpect(jsonPath("$[0].ordrePassage").value(1))
                    .andExpect(jsonPath("$[0].statut").value("EN_ATTENTE"));

            verify(rotationService, times(1)).findAll();
        }

        @Test
        @DisplayName("200 - Retourne une liste vide")
        void getAll_devraitRetourner200AvecListeVide() throws Exception {
            when(rotationService.findAll()).thenReturn(List.of());

            mockMvc.perform(get("/api/rotations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }
    }

    // ─── GET /api/rotations/{id} ──────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/rotations/{id}")
    class GetById {

        @Test
        @DisplayName("200 - Rotation trouvée")
        void getById_devraitRetourner200() throws Exception {
            when(rotationService.findById(1L)).thenReturn(Optional.of(rotation));

            mockMvc.perform(get("/api/rotations/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.evaluateurId").value(3));
        }

        @Test
        @DisplayName("404 - Rotation introuvable")
        void getById_devraitRetourner404() throws Exception {
            when(rotationService.findById(99L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/rotations/99"))
                    .andExpect(status().isNotFound());
        }
    }

    // ─── GET /api/rotations/group/{groupId} ───────────────────────────────────

    @Nested
    @DisplayName("GET /api/rotations/group/{groupId}")
    class GetByGroup {

        @Test
        @DisplayName("200 - Retourne les rotations d'un groupe")
        void getByGroup_devraitRetourner200() throws Exception {
            when(rotationService.findByGroup(1L)).thenReturn(List.of(rotation));

            mockMvc.perform(get("/api/rotations/group/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(1))
                    .andExpect(jsonPath("$[0].studentGroup.id").value(1));

            verify(rotationService, times(1)).findByGroup(1L);
        }

        @Test
        @DisplayName("200 - Liste vide si aucune rotation pour ce groupe")
        void getByGroup_devraitRetournerListeVide() throws Exception {
            when(rotationService.findByGroup(99L)).thenReturn(List.of());

            mockMvc.perform(get("/api/rotations/group/99"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }
    }

    // ─── POST /api/rotations ──────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/rotations")
    class Create {

        @Test
        @DisplayName("200 - Rotation créée avec succès")
        void create_devraitRetourner200() throws Exception {
            when(rotationService.save(any(Rotation.class))).thenReturn(rotation);

            mockMvc.perform(post("/api/rotations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rotation)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statut").value("EN_ATTENTE"))
                    .andExpect(jsonPath("$.ordrePassage").value(1));

            verify(rotationService, times(1)).save(any(Rotation.class));
        }
    }

    // ─── PUT /api/rotations/{id} ──────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/rotations/{id}")
    class Update {

        @Test
        @DisplayName("200 - Rotation mise à jour")
        void update_devraitRetourner200() throws Exception {
            Rotation updated = new Rotation();
            updated.setId(1L);
            updated.setOrdrePassage(2);
            updated.setStatut(RotationStatus.EN_COURS);
            updated.setEvaluateurId(5L);

            when(rotationService.update(eq(1L), any(Rotation.class))).thenReturn(updated);

            mockMvc.perform(put("/api/rotations/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updated)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ordrePassage").value(2))
                    .andExpect(jsonPath("$.statut").value("EN_COURS"));
        }

        @Test
        @DisplayName("404 - Rotation introuvable à la mise à jour")
        void update_devraitRetourner404SiIntrouvable() throws Exception {
            when(rotationService.update(eq(99L), any(Rotation.class)))
                    .thenThrow(new RuntimeException("Rotation non trouvée avec l'id : 99"));

            mockMvc.perform(put("/api/rotations/99")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rotation)))
                    .andExpect(status().isNotFound());
        }
    }

    // ─── DELETE /api/rotations/{id} ───────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/rotations/{id}")
    class Delete {

        @Test
        @DisplayName("204 - Rotation supprimée")
        void delete_devraitRetourner204() throws Exception {
            doNothing().when(rotationService).delete(1L);

            mockMvc.perform(delete("/api/rotations/1"))
                    .andExpect(status().isNoContent());

            verify(rotationService, times(1)).delete(1L);
        }
    }
}
