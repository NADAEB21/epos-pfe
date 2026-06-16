package tn.epos.scoring_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import tn.epos.scoring_service.dto.RotationDTO; // Added Import
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationStatus;
import tn.epos.scoring_service.entities.StudentGroup;
import tn.epos.scoring_service.service.RotationService;
import tn.epos.scoring_service.repositories.IRotationRepository;
import tn.epos.scoring_service.repositories.IStudentGroupRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RotationController.class)
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(TestSecurityConfig.class)
@DisplayName("RotationController - Tests unitaires")
class RotationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RotationService rotationService;

    @MockBean
    private IRotationRepository iRotationRepository;

    @MockBean
    private IStudentGroupRepository studentGroupRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Rotation rotation;
    private RotationDTO rotationDto;

    @BeforeEach
    void setUp() {
        StudentGroup group = new StudentGroup();
        group.setId(1L);
        group.setNumeroGroupe(1);

        rotation = new Rotation();
        rotation.setId(1L);
        rotation.setEvaluateurId(3L);
        rotation.setStationId(7L);
        rotation.setOrdrePassage(1);
        rotation.setDebutCreneau(LocalDateTime.of(2024, 6, 15, 9, 0));
        rotation.setStatut(RotationStatus.EN_ATTENTE);
        rotation.setStudentGroup(group);

        // This matches our new flat DTO structure
        rotationDto = new RotationDTO(1L, 3L, 7L, 1, rotation.getDebutCreneau(), RotationStatus.EN_ATTENTE, 1L);
    }

    @Nested
    @DisplayName("GET /api/rotations")
    class GetAll {
        @Test
        @DisplayName("200 - Retourne toutes les rotations")
        void getAll_devraitRetourner200AvecListe() throws Exception {
            when(rotationService.findAll()).thenReturn(List.of(rotation));

            mockMvc.perform(get("/api/rotations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].studentGroupId").value(1)); // Updated: studentGroup.id -> studentGroupId
        }
    }

    @Nested
    @DisplayName("GET /api/rotations/{id}")
    class GetById {
        @Test
        @DisplayName("200 - Rotation trouvée")
        void getById_devraitRetourner200() throws Exception {
            when(rotationService.findById(1L)).thenReturn(Optional.of(rotation));

            mockMvc.perform(get("/api/rotations/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.evaluateurId").value(3));
        }
    }

    @Nested
    @DisplayName("GET /api/rotations/group/{groupId}")
    class GetByGroup {
        @Test
        @DisplayName("200 - Retourne les rotations d'un groupe")
        void getByGroup_devraitRetourner200() throws Exception {
            when(rotationService.findByGroup(1L)).thenReturn(List.of(rotation));

            mockMvc.perform(get("/api/rotations/group/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].studentGroupId").value(1)); // Updated
        }
    }

    @Nested
    @DisplayName("POST /api/rotations")
    class Create {
        @Test
        @DisplayName("201 - Rotation créée avec succès")
        void create_devraitRetourner200() throws Exception {
            when(rotationService.save(any(Rotation.class))).thenReturn(rotation);

            // Send DTO, not Entity
            mockMvc.perform(post("/api/rotations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rotationDto)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.statut").value("EN_ATTENTE"));
        }
    }

    @Nested
    @DisplayName("PUT /api/rotations/{id}")
    class Update {
        @Test
        @DisplayName("200 - Rotation mise à jour")
        void update_devraitRetourner200() throws Exception {
            when(rotationService.update(eq(1L), any(Rotation.class))).thenReturn(rotation);

            mockMvc.perform(put("/api/rotations/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(rotationDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1));
        }
    }

    @Nested
    @DisplayName("DELETE /api/rotations/{id}")
    class Delete {
        @Test
        @DisplayName("200 - Rotation supprimée")
        void delete_devraitRetourner204() throws Exception {
            doNothing().when(rotationService).delete(1L);

            mockMvc.perform(delete("/api/rotations/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }
}