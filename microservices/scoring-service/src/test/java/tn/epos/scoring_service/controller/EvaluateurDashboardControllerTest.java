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
import org.springframework.security.access.AccessDeniedException;
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

    // =========================================================================
    // GET /api/evaluateur/rotations/{rotationId}/groupe
    //
    // Remplace GET /stations/{stationId}/lots/{lotNumero} : (stationId, lotNumero)
    // était ambigu — un évaluateur reçoit PLUSIEURS rotations pour un même lot
    // (une par groupe qui passe à sa station). rotationId identifie sans
    // ambiguïté le groupe courant.
    // =========================================================================
    @Nested
    @DisplayName("GET /api/evaluateur/rotations/{rotationId}/groupe")
    class GetGroupeDetail {
        @Test
        @DisplayName("200 - Retourne le détail du groupe courant pour l'évaluateur")
        void getGroupeDetail_devraitRetourner200() throws Exception {
            LotDetailResponse resp = LotDetailResponse.builder().id(1L).numero(2).total(4).build();

            when(dashboardService.getGroupeDetail(1L, EVAL_ID)).thenReturn(resp);

            mockMvc.perform(get("/api/evaluateur/rotations/1/groupe")
                            .with(jwt().jwt(j -> j.claim("userId", EVAL_ID))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.numero").value(2))
                    .andExpect(jsonPath("$.data.total").value(4));
        }

        @Test
        @DisplayName("404 - Si la rotation n'existe pas")
        void getGroupeDetail_introuvable_devraitRetourner404() throws Exception {
            when(dashboardService.getGroupeDetail(anyLong(), anyLong()))
                    .thenThrow(new tn.epos.common.exception.ResourceNotFoundException("Rotation introuvable"));

            mockMvc.perform(get("/api/evaluateur/rotations/99/groupe")
                            .with(jwt().jwt(j -> j.claim("userId", EVAL_ID))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("403 - Si la rotation n'appartient pas à l'évaluateur appelant")
        void getGroupeDetail_horsPerimetre_devraitRetourner403() throws Exception {
            when(dashboardService.getGroupeDetail(anyLong(), anyLong()))
                    .thenThrow(new AccessDeniedException("Cette rotation n'est pas assignée à cet évaluateur."));

            mockMvc.perform(get("/api/evaluateur/rotations/1/groupe")
                            .with(jwt().jwt(j -> j.claim("userId", EVAL_ID))))
                    .andExpect(status().isForbidden());
        }
    }

    // =========================================================================
    // POST /api/evaluateur/rotations/{rotationId}/suivant  (#209 : un ACTE, donc un POST)
    // =========================================================================
    @Nested
    @DisplayName("POST /api/evaluateur/rotations/{rotationId}/suivant")
    class GetGroupeSuivant {
        @Test
        @DisplayName("200 - Avance : ouvre le rang suivant et le retourne")
        void getGroupeSuivant_devraitRetourner200() throws Exception {
            LotDetailResponse resp = LotDetailResponse.builder().id(2L).numero(3).total(4).build();

            when(dashboardService.avancerGroupe(1L, EVAL_ID)).thenReturn(resp);

            mockMvc.perform(post("/api/evaluateur/rotations/1/suivant")
                            .with(jwt().jwt(j -> j.claim("userId", EVAL_ID))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(2))
                    .andExpect(jsonPath("$.data.numero").value(3));
        }

        @Test
        @DisplayName("404 - Dernier passage de la station : rien à ouvrir")
        void getGroupeSuivant_aucunSuivant_devraitRetourner404() throws Exception {
            when(dashboardService.avancerGroupe(anyLong(), anyLong()))
                    .thenThrow(new tn.epos.common.exception.ResourceNotFoundException(
                            "Aucun groupe suivant : c'était le dernier passage de cette station pour ce lot."));

            mockMvc.perform(post("/api/evaluateur/rotations/1/suivant")
                            .with(jwt().jwt(j -> j.claim("userId", EVAL_ID))))
                    .andExpect(status().isNotFound());
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

    // =========================================================================
    // POST /api/evaluateur/rotations/{rotationId}/valider
    //
    // Remplace l'appel Flutter cassé vers /rotations/{lotId}/valider (404) :
    // c'est désormais un endpoint réel qui clôture la ROTATION courante
    // (le groupe courant à cette station), pas le lot entier.
    // =========================================================================
    @Nested
    @DisplayName("POST /api/evaluateur/rotations/{rotationId}/valider")
    class ValiderGroupe {
        @Test
        @DisplayName("200 - Valide le groupe courant pour cette station")
        void validerGroupe_devraitRetourner200() throws Exception {
            mockMvc.perform(post("/api/evaluateur/rotations/1/valider")
                            .with(jwt().jwt(j -> j.claim("userId", EVAL_ID))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Groupe validé pour cette station"));

            verify(dashboardService).validerGroupe(1L, EVAL_ID);
        }

        @Test
        @DisplayName("400 - Si le groupe est déjà validé pour cette station")
        void validerGroupe_dejaValide_devraitRetourner400() throws Exception {
            doThrow(new tn.epos.common.exception.BusinessException(
                    "Ce groupe est déjà validé pour cette station."))
                    .when(dashboardService).validerGroupe(anyLong(), anyLong());

            mockMvc.perform(post("/api/evaluateur/rotations/1/valider")
                            .with(jwt().jwt(j -> j.claim("userId", EVAL_ID))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("403 - Si la rotation n'appartient pas à l'évaluateur appelant")
        void validerGroupe_horsPerimetre_devraitRetourner403() throws Exception {
            doThrow(new AccessDeniedException("Cette rotation n'est pas assignée à cet évaluateur."))
                    .when(dashboardService).validerGroupe(anyLong(), anyLong());

            mockMvc.perform(post("/api/evaluateur/rotations/1/valider")
                            .with(jwt().jwt(j -> j.claim("userId", EVAL_ID))))
                    .andExpect(status().isForbidden());
        }
    }

    // Forçage manuel côté responsable/admin — logique interne inchangée,
    // toujours accessible via /lots/{lotId}/valider (restreint désormais à
    // SUPER_ADMIN / RESPONSABLE_MATIERE au niveau du @PreAuthorize méthode).
    @Nested
    @DisplayName("POST /api/evaluateur/lots/{id}/valider")
    class ValiderLot {
        @Test
        @DisplayName("200 - Valide le lot complet (forçage manuel responsable)")
        void validerLot_devraitRetourner200() throws Exception {
            mockMvc.perform(post("/api/evaluateur/lots/10/valider")
                            .with(jwt().jwt(j -> j.claim("userId", EVAL_ID))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Lot 10 validé"));

            verify(dashboardService).validerLot(10L, EVAL_ID);
        }
    }
}