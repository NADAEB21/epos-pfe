package tn.epos.scoring_service.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.scoring_service.config.TestSecurityConfig;
import tn.epos.scoring_service.dto.GenerationResult;
import tn.epos.scoring_service.service.RotationGenerationService;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RotationGenerationController.class)
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(TestSecurityConfig.class)
@DisplayName("RotationGenerationController - Tests unitaires")
class RotationGenerationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RotationGenerationService generationService;

    @Test
    @DisplayName("201 - génère les rotations du lot et renvoie le résumé")
    void generer_devraitRetourner201AvecResume() throws Exception {
        when(generationService.generateForLot(eq(5L)))
                .thenReturn(new GenerationResult(1, 3, 3, 3, 9, 18, 6, 1, null));

        mockMvc.perform(post("/api/rotations/lots/{lotId}/generer", 5L))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.groupes").value(3))
                .andExpect(jsonPath("$.data.rotations").value(9))
                .andExpect(jsonPath("$.data.assignments").value(18))
                .andExpect(jsonPath("$.data.etudiantsAbsents").value(1));
    }
}
