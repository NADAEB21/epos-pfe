package tn.epos.scoring_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.scoring_service.config.TestSecurityConfig;
import tn.epos.scoring_service.dto.PresenceBulkRequest;
import tn.epos.scoring_service.dto.PresenceResult;
import tn.epos.scoring_service.dto.RepartitionResult;
import tn.epos.scoring_service.service.LotAssignmentService;
import tn.epos.scoring_service.service.LotDemarrageService;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LotAssignmentController.class)
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(TestSecurityConfig.class)
@DisplayName("LotAssignmentController - Tests unitaires")
class LotAssignmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LotAssignmentService lotAssignmentService;

    @MockBean
    private LotDemarrageService lotDemarrageService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("201 - répartit les étudiants en lots et renvoie le résumé")
    void repartir_devraitRetourner201() throws Exception {
        when(lotAssignmentService.repartir(eq(10L))).thenReturn(
                new RepartitionResult(3, 12, 30, List.of(
                        new RepartitionResult.LotInfo(1L, 1, 12),
                        new RepartitionResult.LotInfo(2L, 2, 12),
                        new RepartitionResult.LotInfo(3L, 3, 6))));

        mockMvc.perform(post("/api/lots/examens/{examenId}/repartir", 10L))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.lots").value(3))
                .andExpect(jsonPath("$.data.lotSize").value(12))
                .andExpect(jsonPath("$.data.etudiantsRepartis").value(30))
                .andExpect(jsonPath("$.data.details[2].taille").value(6));
    }

    @Test
    @DisplayName("200 - présence avec absents listés")
    void presence_avecAbsents() throws Exception {
        when(lotAssignmentService.markPresence(eq(5L), eq(List.of(2L, 4L))))
                .thenReturn(new PresenceResult(5L, 6, 4, 2));

        mockMvc.perform(patch("/api/lots/{lotId}/presence", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PresenceBulkRequest(List.of(2L, 4L)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.presents").value(4))
                .andExpect(jsonPath("$.data.absents").value(2));
    }

    @Test
    @DisplayName("200 - présence sans corps → tout présent (absents null)")
    void presence_sansCorps() throws Exception {
        when(lotAssignmentService.markPresence(eq(5L), isNull()))
                .thenReturn(new PresenceResult(5L, 6, 6, 0));

        mockMvc.perform(patch("/api/lots/{lotId}/presence", 5L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.presents").value(6))
                .andExpect(jsonPath("$.data.absents").value(0));
    }
}
