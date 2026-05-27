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
import tn.epos.scoring_service.entities.Etudiant;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.entities.LotStatus;
import tn.epos.scoring_service.service.ExamenParticipationService;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ExamenParticipationController.class)
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(TestSecurityConfig.class)
@DisplayName("ExamenParticipationController - Tests unitaires")
class ExamenParticipationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExamenParticipationService participationService;

    private ObjectMapper objectMapper;
    private ExamenParticipation participation;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        Etudiant etudiant = new Etudiant();
        etudiant.setId(1L);
        etudiant.setNom("Ben Ali");
        etudiant.setPrenom("Mohamed");

        Lot lot = new Lot();
        lot.setId(1L);
        lot.setNumeroLot(1);
        lot.setStatut(LotStatus.EN_ATTENTE);

        participation = new ExamenParticipation();
        participation.setId(1L);
        participation.setExamen_id(10L);
        participation.setNum_echantillon("ECH-001");
        participation.setNote(15.5f);
        participation.setEst_present(true);
        participation.setEtudiant(etudiant);
        participation.setLot(lot);
    }

    // ─── GET /api/participations ──────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/participations")
    class GetAll {

        @Test
        @DisplayName("200 - Retourne toutes les participations")
        void getAll_devraitRetourner200AvecListe() throws Exception {
            when(participationService.getAll()).thenReturn(List.of(participation));

            mockMvc.perform(get("/api/participations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].examen_id").value(10))
                    .andExpect(jsonPath("$.data[0].num_echantillon").value("ECH-001"))
                    .andExpect(jsonPath("$.data[0].est_present").value(true));

            verify(participationService, times(1)).getAll();
        }

        @Test
        @DisplayName("200 - Retourne une liste vide")
        void getAll_devraitRetourner200AvecListeVide() throws Exception {
            when(participationService.getAll()).thenReturn(List.of());

            mockMvc.perform(get("/api/participations"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    // ─── GET /api/participations/{id} ────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/participations/{id}")
    class GetById {

        @Test
        @DisplayName("200 - Participation trouvée")
        void getById_devraitRetourner200() throws Exception {
            when(participationService.getById(1L)).thenReturn(Optional.of(participation));

            mockMvc.perform(get("/api/participations/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.note").value(15.5));
        }

        @Test
        @DisplayName("404 - Participation introuvable")
        void getById_devraitRetourner404() throws Exception {
            when(participationService.getById(99L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/participations/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ─── POST /api/participations ─────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/participations")
    class Create {

        @Test
        @DisplayName("201 - Participation créée avec succès")
        void create_devraitRetourner200() throws Exception {
            when(participationService.save(any(ExamenParticipation.class))).thenReturn(participation);

            mockMvc.perform(post("/api/participations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(participation)))
                    .andExpect(status().isCreated()) // Matches HttpStatus.CREATED
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.examen_id").value(10))
                    .andExpect(jsonPath("$.data.num_echantillon").value("ECH-001"));

            verify(participationService, times(1)).save(any(ExamenParticipation.class));
        }
    }

    // ─── PUT /api/participations/{id} ─────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/participations/{id}")
    class Update {

        @Test
        @DisplayName("200 - Participation mise à jour")
        void update_devraitRetourner200() throws Exception {
            ExamenParticipation updated = new ExamenParticipation();
            updated.setId(1L);
            updated.setNote(18.0f);
            updated.setEst_present(true);

            when(participationService.getById(1L)).thenReturn(Optional.of(participation));
            when(participationService.save(any(ExamenParticipation.class))).thenReturn(updated);

            mockMvc.perform(put("/api/participations/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updated)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.note").value(18.0));
        }

        @Test
        @DisplayName("404 - Participation introuvable à la mise à jour")
        void update_devraitRetourner404SiIntrouvable() throws Exception {
            when(participationService.getById(99L)).thenReturn(Optional.empty());

            mockMvc.perform(put("/api/participations/99")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(participation)))
                    .andExpect(status().isNotFound());
        }
    }

    // ─── DELETE /api/participations/{id} ─────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/participations/{id}")
    class Delete {

        @Test
        @DisplayName("200 - Participation supprimée")
        void delete_devraitRetourner204() throws Exception {
            when(participationService.getById(1L)).thenReturn(Optional.of(participation));
            doNothing().when(participationService).delete(1L);

            mockMvc.perform(delete("/api/participations/1"))
                    .andExpect(status().isOk()); // Returns 200 with success wrapper

            verify(participationService, times(1)).delete(1L);
        }

        @Test
        @DisplayName("404 - Participation introuvable à la suppression")
        void delete_devraitRetourner404SiIntrouvable() throws Exception {
            when(participationService.getById(99L)).thenReturn(Optional.empty());

            mockMvc.perform(delete("/api/participations/99"))
                    .andExpect(status().isNotFound());

            verify(participationService, never()).delete(anyLong());
        }
    }
}