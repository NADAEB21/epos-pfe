package tn.epos.scoring_service.controller;

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
import tn.epos.scoring_service.dto.SubstitutionResult;
import tn.epos.scoring_service.service.EvaluateurSubstitutionService;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EvaluateurSubstitutionController.class)
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(TestSecurityConfig.class)
@DisplayName("EvaluateurSubstitutionController (ADR-0017)")
class EvaluateurSubstitutionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EvaluateurSubstitutionService substitutionService;

    @Test
    @DisplayName("POST — renvoie le bilan : transférés vs conservés")
    void remplacer_devraitRetournerLeBilan() throws Exception {
        when(substitutionService.remplacer(eq(266L), eq(87L), eq(2L), anyString(), anyLong()))
                .thenReturn(new SubstitutionResult(266L, 87L, 3L, 2L, 2, 1,
                        "2 groupe(s) transféré(s). 1 groupe(s) déjà terminé(s) restent au nom de "
                                + "l'évaluateur précédent."));

        mockMvc.perform(post("/api/lots/266/stations/87/remplacer-evaluateur")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nouvelEvaluateurId\":2,\"motif\":\"Urgence familiale\"}")
                        .with(jwt().jwt(j -> j.claim("userId", 9L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rotationsTransferees").value(2))
                // La question du responsable : le travail fait reste-t-il au bon nom ? Oui.
                .andExpect(jsonPath("$.data.rotationsConservees").value(1))
                .andExpect(jsonPath("$.data.ancienEvaluateur").value(3))
                .andExpect(jsonPath("$.data.nouvelEvaluateur").value(2));

        verify(substitutionService).remplacer(eq(266L), eq(87L), eq(2L), anyString(), anyLong());
    }

    /**
     * Le motif est obligatoire : une suppléance en pleine épreuve doit pouvoir
     * s'expliquer après coup. Un champ facultatif serait laissé vide, et la trace
     * ne vaudrait plus rien.
     */
    @Test
    @DisplayName("POST — motif vide : refusé avant même d'atteindre le service")
    void remplacer_motifVide_devraitEtreRefuse() throws Exception {
        mockMvc.perform(post("/api/lots/266/stations/87/remplacer-evaluateur")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nouvelEvaluateurId\":2,\"motif\":\"\"}")
                        .with(jwt().jwt(j -> j.claim("userId", 9L))))
                .andExpect(status().isBadRequest());

        verify(substitutionService, org.mockito.Mockito.never())
                .remplacer(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST — nouvel évaluateur absent : refusé")
    void remplacer_sansEvaluateur_devraitEtreRefuse() throws Exception {
        mockMvc.perform(post("/api/lots/266/stations/87/remplacer-evaluateur")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motif\":\"Urgence\"}")
                        .with(jwt().jwt(j -> j.claim("userId", 9L))))
                .andExpect(status().isBadRequest());
    }
}
