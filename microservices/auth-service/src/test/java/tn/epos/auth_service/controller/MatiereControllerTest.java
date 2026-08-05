package tn.epos.auth_service.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.auth_service.config.JwtAuthenticationFilter;
import tn.epos.auth_service.dto.MatiereImportResult;
import tn.epos.auth_service.dto.MatiereResponse;
import tn.epos.auth_service.service.JwtService;
import tn.epos.auth_service.service.MatiereService;
import tn.epos.auth_service.service.UserDetailsServiceImpl;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #134 — le catalogue des matières. Ce que cette tranche épingle :
 * la LECTURE reste ouverte à tout authentifié (les pickers en dépendent),
 * chaque ÉCRITURE est SUPER_ADMIN seul (ADR-0018 D5 — le catalogue est son
 * domaine, pas celui d'un responsable), et il n'existe AUCUN DELETE : le
 * retrait motivé est l'unique acte de fermeture.
 * Same slice pattern as {@link UserControllerTest}: filters off, method-security on.
 */
@WebMvcTest(MatiereController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(MatiereControllerTest.MethodSecurityConfig.class)
@ActiveProfiles("test")
class MatiereControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private MatiereService matiereService;

    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private JwtService jwtService;
    @MockBean private UserDetailsServiceImpl userDetailsService;

    @org.springframework.boot.test.context.TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    private static MatiereResponse matiere(long id, String code, String libelle, boolean active) {
        return new MatiereResponse(id, code, libelle, active, null, null, null);
    }

    // -------------------------------------------------------------------------
    // Lecture — ouverte à tout authentifié, retirées comprises
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(authorities = "ROLE_SUPER_ADMIN")
    void list_superAdmin_returns200() throws Exception {
        when(matiereService.list()).thenReturn(List.of(
                matiere(1L, "CHIM", "Chimie", true),
                matiere(2L, "PHARM", "Pharmacologie", false)
        ));

        mockMvc.perform(get("/api/v1/matieres"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].code").value("CHIM"))
                // La liste porte AUSSI les retirées, avec leur drapeau : les
                // libellés historiques (« Matière 7 » sinon) en dépendent.
                .andExpect(jsonPath("$.data[1].active").value(false));
    }

    @Test
    @WithMockUser(authorities = "ROLE_RESPONSABLE_MATIERE:5")
    void list_scopedResponsable_returns200() throws Exception {
        when(matiereService.list()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/matieres"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @WithMockUser(authorities = "ROLE_EVALUATEUR")
    void list_evaluateur_returns200() throws Exception {
        // Picker data is non-sensitive — any authenticated role can fetch it.
        when(matiereService.list()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/matieres"))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // Écritures — SUPER_ADMIN seul (ADR-0018 D5)
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(authorities = "ROLE_SUPER_ADMIN")
    void creer_superAdmin_returns201() throws Exception {
        when(matiereService.creer(any(), any(), any()))
                .thenReturn(matiere(7L, "BIOCHIM", "Biochimie clinique", true));

        mockMvc.perform(post("/api/v1/matieres")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"BIOCHIM\",\"libelle\":\"Biochimie clinique\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    @WithMockUser(authorities = "ROLE_RESPONSABLE_MATIERE:5")
    void creer_responsable_returns403() throws Exception {
        // Le catalogue est le domaine du SUPER_ADMIN — un responsable gère SES
        // examens, pas la liste des matières de la faculté (ADR-0018 D5).
        mockMvc.perform(post("/api/v1/matieres")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"BIOCHIM\",\"libelle\":\"Biochimie clinique\"}"))
                .andExpect(status().isForbidden());

        verify(matiereService, never()).creer(any(), any(), any());
    }

    @Test
    @WithMockUser(authorities = "ROLE_SUPER_ADMIN")
    void creer_codeBlanc_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/matieres")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"  \",\"libelle\":\"Biochimie\"}"))
                .andExpect(status().isBadRequest());

        verify(matiereService, never()).creer(any(), any(), any());
    }

    @Test
    @WithMockUser(authorities = "ROLE_SUPER_ADMIN")
    void modifier_superAdmin_returns200() throws Exception {
        when(matiereService.modifier(eq(3L), any(), any(), any()))
                .thenReturn(matiere(3L, "PHAG", "Pharmacognosie", true));

        mockMvc.perform(put("/api/v1/matieres/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"PHAG\",\"libelle\":\"Pharmacognosie\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.libelle").value("Pharmacognosie"));
    }

    @Test
    @WithMockUser(authorities = "ROLE_EVALUATEUR")
    void modifier_evaluateur_returns403() throws Exception {
        mockMvc.perform(put("/api/v1/matieres/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"PHAG\",\"libelle\":\"Pharmacognosie\"}"))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Retrait / réouverture — motif obligatoire, comme #289
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(authorities = "ROLE_SUPER_ADMIN")
    void retirer_avecMotif_returns200_etPasseLeMotif() throws Exception {
        when(matiereService.retirer(eq(4L), eq("Matière fermée à la rentrée 2026"), any(), any()))
                .thenReturn(matiere(4L, "TOXICO", "Toxicologie", false));

        mockMvc.perform(post("/api/v1/matieres/4/retrait")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motif\":\"Matière fermée à la rentrée 2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));

        verify(matiereService).retirer(eq(4L), eq("Matière fermée à la rentrée 2026"), any(), any());
    }

    @Test
    @WithMockUser(authorities = "ROLE_SUPER_ADMIN")
    void retirer_sansMotif_returns400() throws Exception {
        // Le motif est une condition, pas une décoration (#289 appliqué au catalogue).
        mockMvc.perform(post("/api/v1/matieres/4/retrait")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motif\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(matiereService, never()).retirer(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(authorities = "ROLE_RESPONSABLE_MATIERE:4")
    void retirer_responsable_returns403_memeSurSaPropreMatiere() throws Exception {
        // Même la responsable de la matière 4 ne retire pas SA matière du
        // catalogue : fermer une matière est un acte facultaire, pas pédagogique.
        mockMvc.perform(post("/api/v1/matieres/4/retrait")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motif\":\"tentative\"}"))
                .andExpect(status().isForbidden());

        verify(matiereService, never()).retirer(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(authorities = "ROLE_SUPER_ADMIN")
    void reactiver_returns200() throws Exception {
        when(matiereService.reactiver(eq(4L), eq("La matière reprend"), any(), any()))
                .thenReturn(matiere(4L, "TOXICO", "Toxicologie", true));

        mockMvc.perform(post("/api/v1/matieres/4/reactivation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motif\":\"La matière reprend\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true));
    }

    // -------------------------------------------------------------------------
    // Import en lot
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(authorities = "ROLE_SUPER_ADMIN")
    void importer_superAdmin_renvoieLeVerdictParLigne() throws Exception {
        when(matiereService.importer(anyList(), any(), any())).thenReturn(new MatiereImportResult(
                1, 1, 0, List.of(
                new MatiereImportResult.Row(1, "BIOCHIM", MatiereImportResult.Statut.CREATED, "Créée."),
                new MatiereImportResult.Row(2, "CHIM_THER", MatiereImportResult.Statut.DUPLICATE,
                        "Ce code existe déjà au catalogue."))));

        mockMvc.perform(post("/api/v1/matieres/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"code\":\"BIOCHIM\",\"libelle\":\"Biochimie\"},"
                                + "{\"code\":\"CHIM_THER\",\"libelle\":\"Chimie thérapeutique\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.crees").value(1))
                .andExpect(jsonPath("$.data.doublons").value(1))
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.rows[0].statut").value("CREATED"))
                .andExpect(jsonPath("$.data.rows[1].statut").value("DUPLICATE"));
    }

    @Test
    @WithMockUser(authorities = "ROLE_RESPONSABLE_MATIERE:5")
    void importer_responsable_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/matieres/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"code\":\"X\",\"libelle\":\"Y\"}]"))
                .andExpect(status().isForbidden());

        verify(matiereService, never()).importer(anyList(), any(), any());
    }
}
