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
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.entities.LotStatus;
import tn.epos.scoring_service.entities.StudentGroup;
import tn.epos.scoring_service.service.StudentGroupService;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = StudentGroupController.class)
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(TestSecurityConfig.class)
@DisplayName("StudentGroupController - Tests unitaires")
class StudentGroupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StudentGroupService studentGroupService;

    private ObjectMapper objectMapper;
    private StudentGroup group;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        Lot lot = new Lot();
        lot.setId(1L);
        lot.setNumeroLot(1);
        lot.setTailleLot(20);
        lot.setStatut(LotStatus.EN_ATTENTE);
        lot.setEvaluateurId(5L);
        lot.setExamenId(10L);

        group = new StudentGroup();
        group.setId(1L);
        group.setNumeroGroupe(1);
        group.setLot(lot);
    }

    // ─── GET /api/student-groups ──────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/student-groups")
    class GetAll {

        @Test
        @DisplayName("200 - Retourne tous les groupes")
        void getAll_devraitRetourner200AvecListe() throws Exception {
            when(studentGroupService.findAll()).thenReturn(List.of(group));

            mockMvc.perform(get("/api/student-groups"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].numeroGroupe").value(1))
                    .andExpect(jsonPath("$.data[0].lot.id").value(1));

            verify(studentGroupService, times(1)).findAll();
        }

        @Test
        @DisplayName("200 - Retourne une liste vide")
        void getAll_devraitRetourner200AvecListeVide() throws Exception {
            when(studentGroupService.findAll()).thenReturn(List.of());

            mockMvc.perform(get("/api/student-groups"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    // ─── GET /api/student-groups/{id} ────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/student-groups/{id}")
    class GetById {

        @Test
        @DisplayName("200 - Groupe trouvé")
        void getById_devraitRetourner200() throws Exception {
            when(studentGroupService.findById(1L)).thenReturn(Optional.of(group));

            mockMvc.perform(get("/api/student-groups/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.numeroGroupe").value(1));
        }

        @Test
        @DisplayName("404 - Groupe introuvable")
        void getById_devraitRetourner404() throws Exception {
            when(studentGroupService.findById(99L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/student-groups/99"))
                    .andExpect(status().isNotFound());
        }
    }

    // ─── GET /api/student-groups/lot/{lotId} ──────────────────────────────────

    @Nested
    @DisplayName("GET /api/student-groups/lot/{lotId}")
    class GetByLot {

        @Test
        @DisplayName("200 - Retourne les groupes d'un lot")
        void getByLot_devraitRetourner200() throws Exception {
            when(studentGroupService.findByLotId(1L)).thenReturn(List.of(group));

            mockMvc.perform(get("/api/student-groups/lot/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(1));

            verify(studentGroupService, times(1)).findByLotId(1L);
        }
    }

    // ─── POST /api/student-groups ─────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/student-groups")
    class Create {

        @Test
        @DisplayName("201 - Groupe créé avec succès")
        void create_devraitRetourner200() throws Exception {
            when(studentGroupService.save(any(StudentGroup.class))).thenReturn(group);

            mockMvc.perform(post("/api/student-groups")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(group)))
                    .andExpect(status().isCreated()) // Matches controller 201
                    .andExpect(jsonPath("$.data.numeroGroupe").value(1))
                    .andExpect(jsonPath("$.data.lot.numeroLot").value(1));

            verify(studentGroupService, times(1)).save(any(StudentGroup.class));
        }
    }

    // ─── PUT /api/student-groups/{id} ────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/student-groups/{id}")
    class Update {

        @Test
        @DisplayName("200 - Groupe mis à jour")
        void update_devraitRetourner200() throws Exception {
            Lot nouveauLot = new Lot();
            nouveauLot.setId(2L);
            nouveauLot.setNumeroLot(2);
            nouveauLot.setStatut(LotStatus.EN_COURS);

            StudentGroup updated = new StudentGroup();
            updated.setId(1L);
            updated.setNumeroGroupe(3);
            updated.setLot(nouveauLot);

            when(studentGroupService.update(eq(1L), any(StudentGroup.class))).thenReturn(updated);

            mockMvc.perform(put("/api/student-groups/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updated)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.numeroGroupe").value(3));
        }
    }

    // ─── DELETE /api/student-groups/{id} ─────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/student-groups/{id}")
    class Delete {

        @Test
        @DisplayName("200 - Groupe supprimé") // Changed from 204 to 200
        void delete_devraitRetourner204() throws Exception {
            doNothing().when(studentGroupService).delete(1L);

            mockMvc.perform(delete("/api/student-groups/1"))
                    .andExpect(status().isOk()) // Standardized wrapper
                    .andExpect(jsonPath("$.success").value(true));

            verify(studentGroupService, times(1)).delete(1L);
        }
    }
}