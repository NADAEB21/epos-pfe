package tn.epos.scoring_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import tn.epos.scoring_service.dto.dashboard.*;
import tn.epos.scoring_service.service.EvaluateurDashboardService;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EvaluateurDashboardController.class)
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(TestSecurityConfig.class)
@DisplayName("EvaluateurDashboardController - Tests complets")
class EvaluateurDashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EvaluateurDashboardService dashboardService;

    @Autowired
    private ObjectMapper objectMapper;

    private final Long EVAL_ID = 123L;

    @Nested
    @DisplayName("Tests d'extraction du JWT (userId)")
    class JwtExtractionTests {

        @Test
        @DisplayName("500 - Erreur si le claim userId est absent du token")
        void extraction_userIdAbsent_devraitRetourner500() throws Exception {
            mockMvc.perform(get("/api/evaluateur/dashboard")
                            .with(jwt().jwt(j -> j.claim("sub", "someone")))) // Pas de userId
                    .andExpect(status().isInternalServerError());
        }

        @Test
        @DisplayName("500 - Erreur si le claim userId n'est pas un nombre")
        void extraction_userIdInvalide_devraitRetourner500() throws Exception {
            mockMvc.perform(get("/api/evaluateur/dashboard")
                            .with(jwt().jwt(j -> j.claim("userId", "abc")))) // String au lieu de Number
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("GET /api/evaluateur/dashboard")
    class GetDashboard {
        @Test
        @DisplayName("200 - Retourne les données agrégées du dashboard")
        void getDashboard_devraitRetourner200() throws Exception {
            EvaluateurDashboardResponse resp = EvaluateurDashboardResponse.builder()
                    .sessions(List.of(SessionResponse.builder().id(1L).statut("EN_COURS").build()))
                    .stats(StatsResponse.builder().totalLots(5).build())
                    .build();

            when(dashboardService.buildDashboard(EVAL_ID)).thenReturn(resp);

            mockMvc.perform(get("/api/evaluateur/dashboard")
                            //.with(jwt().claim("userId", EVAL_ID)))
                    .with(jwt().jwt(j -> j.claim("userId", EVAL_ID))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.sessions[0].id").value(1))
                    .andExpect(jsonPath("$.data.stats.totalLots").value(5));
        }
    }

    @Nested
    @DisplayName("Tests de Validation DTO")
    class ValidationTests {

        @Test
        @DisplayName("400 - Échec si la requête de validation est incomplète")
        void validerEtudiant_invalide_devraitRetourner400() throws Exception {
            ValiderEtudiantRequest req = new ValiderEtudiantRequest();
            // On ne met pas de grilleId, ce qui devrait déclencher une erreur de validation

            mockMvc.perform(post("/api/evaluateur/etudiants/1/stations/1/valider")
                            .with(jwt().jwt(j -> j.claim("userId", EVAL_ID)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/evaluateur/stations/{id}/lots/{n}")
    class GetLotDetail {
        @Test
        @DisplayName("200 - Retourne le détail d'un lot pour l'évaluateur")
        void getLot_devraitRetourner200() throws Exception {
            LotDetailResponse resp = LotDetailResponse.builder().id(10L).numero(1).build();

            when(dashboardService.getLotDetail(anyLong(), anyInt(), eq(EVAL_ID))).thenReturn(resp);

            mockMvc.perform(get("/api/evaluateur/stations/1/lots/1")
                    .with(jwt().jwt(j -> j.claim("userId", EVAL_ID))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(10));
        }
    }

    @Nested
    @DisplayName("Tests de propagation d'erreurs Service")
    class ServiceExceptionTests {

        @Test
        @DisplayName("404 - Si le lot n'existe pas dans le service")
        void getLot_introuvable_devraitRetourner404() throws Exception {
            // Simulation d'une exception dans le service
            when(dashboardService.getLotDetail(anyLong(), anyInt(), anyLong()))
                    .thenThrow(new tn.epos.common.exception.ResourceNotFoundException("Lot non trouvé"));

            mockMvc.perform(get("/api/evaluateur/stations/1/lots/99")
                            .with(jwt().jwt(j -> j.claim("userId", EVAL_ID))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    @Nested
    @DisplayName("POST /api/evaluateur/notations/saisir")
    class SaisirNotation {
        @Test
        @DisplayName("200 - Enregistre une note partielle")
        void saisirNotation_devraitRetourner200() throws Exception {
            SaisirNotationRequest req = new SaisirNotationRequest(1L, 1L, 1L, 1L, 1.5f);

            mockMvc.perform(post("/api/evaluateur/notations/saisir")
                            .with(jwt().jwt(j -> j.claim("userId", EVAL_ID)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Notation enregistrée"));

            verify(dashboardService).saisirNotation(any(SaisirNotationRequest.class), eq(EVAL_ID));
        }
    }

    @Nested
    @DisplayName("POST /api/evaluateur/etudiants/{id}/stations/{id}/valider")
    class ValiderEtudiant {
        @Test
        @DisplayName("200 - Verrouille les notes d'un étudiant")
        void validerEtudiant_devraitRetourner200() throws Exception {
            ValiderEtudiantRequest req = new ValiderEtudiantRequest();
            req.setAbsent(false);
            req.setCommentaire("Bien");
            req.setGrilleId(1L);

            mockMvc.perform(post("/api/evaluateur/etudiants/1/stations/1/valider")
                            .with(jwt().jwt(j -> j.claim("userId", EVAL_ID)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Notes verrouillées pour l'étudiant 1"));

            verify(dashboardService).validerEtudiant(eq(1L), eq(1L), eq(EVAL_ID), any());
        }
    }

    @Nested
    @DisplayName("POST /api/evaluateur/lots/{id}/valider")
    class ValiderLot {
        @Test
        @DisplayName("200 - Valide le lot complet")
        void validerLot_devraitRetourner200() throws Exception {
            mockMvc.perform(post("/api/evaluateur/lots/10/valider")
                    .with(jwt().jwt(j -> j.claim("userId", EVAL_ID))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Lot 10 validé"));

            verify(dashboardService).validerLot(10L, EVAL_ID);
        }
    }
}