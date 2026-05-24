package tn.epos.auth_service.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.auth_service.config.JwtAuthenticationFilter;
import tn.epos.auth_service.entity.Matiere;
import tn.epos.auth_service.repository.MatiereRepository;
import tn.epos.auth_service.service.JwtService;
import tn.epos.auth_service.service.UserDetailsServiceImpl;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-level coverage for #81 — GET /api/v1/matieres (frontend picker).
 * Same slice pattern as {@link UserControllerTest}: filters off, method-security on.
 */
@WebMvcTest(MatiereController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(MatiereControllerTest.MethodSecurityConfig.class)
@ActiveProfiles("test")
class MatiereControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private MatiereRepository matiereRepository;

    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private JwtService jwtService;
    @MockBean private UserDetailsServiceImpl userDetailsService;

    @org.springframework.boot.test.context.TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Test
    @WithMockUser(authorities = "ROLE_SUPER_ADMIN")
    void list_superAdmin_returns200() throws Exception {
        when(matiereRepository.findAll()).thenReturn(List.of(
                Matiere.builder().id(1L).code("CHIM").libelle("Chimie").build(),
                Matiere.builder().id(2L).code("PHARM").libelle("Pharmacologie").build()
        ));

        mockMvc.perform(get("/api/v1/matieres"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].code").value("CHIM"))
                .andExpect(jsonPath("$.data[1].libelle").value("Pharmacologie"));
    }

    @Test
    @WithMockUser(authorities = "ROLE_RESPONSABLE_MATIERE:5")
    void list_scopedResponsable_returns200() throws Exception {
        when(matiereRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/matieres"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @WithMockUser(authorities = "ROLE_EVALUATEUR")
    void list_evaluateur_returns200() throws Exception {
        // Picker data is non-sensitive — any authenticated role can fetch it.
        when(matiereRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/matieres"))
                .andExpect(status().isOk());
    }
}
