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
import tn.epos.scoring_service.dto.ParticipationDTO;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.entities.LotStatus;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.service.LotAssignmentService;
import tn.epos.scoring_service.service.LotOuvertureService;
import tn.epos.scoring_service.service.SuiviProgressionService;
import tn.epos.scoring_service.service.LotService;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LotController.class)
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(TestSecurityConfig.class)
@DisplayName("LotController - Tests unitaires")
class LotControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LotService lotService;

    @MockBean
    private LotAssignmentService lotAssignmentService;

    @MockBean
    private LotOuvertureService lotOuvertureService;

    @MockBean
    private SuiviProgressionService suiviProgressionService;

    private ObjectMapper objectMapper;
    private Lot lot;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        lot = new Lot();
        lot.setId(1L);
        lot.setNumeroLot(1);
        lot.setTailleLot(20);
        lot.setStatut(LotStatus.EN_ATTENTE);
        lot.setEvaluateurId(5L);
        lot.setExamenId(10L);
    }

    // ─── GET /api/lots ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/lots")
    class GetAll {

        @Test
        @DisplayName("200 - Retourne tous les lots")
        void getAll_devraitRetourner200AvecListe() throws Exception {
            when(lotService.findAll()).thenReturn(List.of(lot));

            mockMvc.perform(get("/api/lots"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].numeroLot").value(1))
                    .andExpect(jsonPath("$.data[0].statut").value("EN_ATTENTE"));

            verify(lotService, times(1)).findAll();
        }

        @Test
        @DisplayName("200 - Retourne une liste vide")
        void getAll_devraitRetourner200AvecListeVide() throws Exception {
            when(lotService.findAll()).thenReturn(List.of());

            mockMvc.perform(get("/api/lots"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    // ─── GET /api/lots/{id} ───────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/lots/{id}")
    class GetById {

        @Test
        @DisplayName("200 - Lot trouvé")
        void getById_devraitRetourner200() throws Exception {
            when(lotService.findById(1L)).thenReturn(Optional.of(lot));

            mockMvc.perform(get("/api/lots/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.tailleLot").value(20))
                    .andExpect(jsonPath("$.data.evaluateurId").value(5));
        }

        @Test
        @DisplayName("404 - Lot introuvable")
        void getById_devraitRetourner404() throws Exception {
            when(lotService.findById(99L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/lots/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ─── POST /api/lots ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/lots")
    class Create {

        @Test
        @DisplayName("201 - Lot créé avec succès")
        void create_devraitRetourner200() throws Exception {
            when(lotService.save(any(Lot.class))).thenReturn(lot);

            mockMvc.perform(post("/api/lots")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(lot)))
                    .andExpect(status().isCreated()) // Matches controller HttpStatus.CREATED
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.numeroLot").value(1))
                    .andExpect(jsonPath("$.data.statut").value("EN_ATTENTE"));

            verify(lotService, times(1)).save(any(Lot.class));
        }
    }

    // ─── PUT /api/lots/{id} ───────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/lots/{id}")
    class Update {

        @Test
        @DisplayName("200 - Lot mis à jour")
        void update_devraitRetourner200() throws Exception {
            Lot updated = new Lot();
            updated.setId(1L);
            updated.setNumeroLot(2);
            updated.setTailleLot(30);
            updated.setStatut(LotStatus.EN_COURS);
            updated.setEvaluateurId(7L);
            updated.setExamenId(12L);

            when(lotService.update(eq(1L), any(Lot.class))).thenReturn(updated);

            mockMvc.perform(put("/api/lots/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updated)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.numeroLot").value(2))
                    .andExpect(jsonPath("$.data.statut").value("EN_COURS"));
        }

        @Test
        @DisplayName("404 - Lot introuvable à la mise à jour")
        void update_devraitRetourner404SiIntrouvable() throws Exception {
            when(lotService.update(eq(99L), any(Lot.class)))
                    .thenThrow(new ResourceNotFoundException("Lot non trouvé avec l'id : 99"));

            mockMvc.perform(put("/api/lots/99")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(lot)))
                    .andExpect(status().isNotFound());
        }
    }

    // ─── DELETE /api/lots/{id} ────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/lots/{id}")
    class Delete {

        @Test
        @DisplayName("200 - Lot supprimé") // Status changed from 204 to 200
        void delete_devraitRetourner204() throws Exception {
            doNothing().when(lotService).delete(1L);

            mockMvc.perform(delete("/api/lots/1"))
                    .andExpect(status().isOk()) // Returns ApiResponse now
                    .andExpect(jsonPath("$.success").value(true));

            verify(lotService, times(1)).delete(1L);
        }
    }

    // ─── PATCH /api/lots/{targetLotId}/etudiants/{participationId} (#165) ──────

    @Nested
    @DisplayName("PATCH /api/lots/{targetLotId}/etudiants/{participationId}")
    class Deplacer {

        @Test
        @DisplayName("200 - Étudiant déplacé, renvoie la participation avec son nouveau lotId")
        void deplacer_devraitRetourner200() throws Exception {
            ParticipationDTO dto = new ParticipationDTO(3L, 10L, "E-3", null, null, 7L, 2L);
            when(lotAssignmentService.deplacerEtudiant(2L, 3L)).thenReturn(dto);

            mockMvc.perform(patch("/api/lots/2/etudiants/3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(3))
                    .andExpect(jsonPath("$.data.lotId").value(2));

            verify(lotAssignmentService, times(1)).deplacerEtudiant(2L, 3L);
        }
    }
}