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
import tn.epos.auth_service.entity.RoleType;
import tn.epos.auth_service.service.JwtService;
import tn.epos.auth_service.service.UserDetailsServiceImpl;
import tn.epos.auth_service.service.UserService;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-level coverage for the #80 widening: GET /api/v1/users is now open
 * to RESPONSABLE_MATIERE (was SUPER_ADMIN-only), and accepts an optional ?role= filter.
 *
 * Filters disabled (addFilters=false) so we don't need to set up the JWT filter chain;
 * @WithMockUser populates SecurityContext directly and @EnableMethodSecurity makes
 * @PreAuthorize on the controller actually evaluate.
 */
@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(UserControllerTest.MethodSecurityConfig.class)
@ActiveProfiles("test")
class UserControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private UserService userService;

    // Required because UserController itself doesn't need these but the auto-scan
    // pulls in JwtAuthenticationFilter (@Component) whose constructor needs them.
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private JwtService jwtService;
    @MockBean private UserDetailsServiceImpl userDetailsService;

    @org.springframework.boot.test.context.TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    // -------------------------------------------------------------------------
    // SpEL gate widening (#80 core)
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(authorities = "ROLE_SUPER_ADMIN")
    void getAllUsers_superAdmin_returns200() throws Exception {
        when(userService.getAllUsers(isNull())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = "ROLE_RESPONSABLE_MATIERE:5")
    void getAllUsers_scopedResponsable_returns200() throws Exception {
        // The whole point of #80: a scoped responsable (matiere=5) can now list users.
        when(userService.getAllUsers(isNull())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = "ROLE_EVALUATEUR")
    void getAllUsers_evaluateur_isDenied() throws Exception {
        // EVALUATEUR is still locked out — only the responsable widening was asked for.
        // Without the full security filter chain, AccessDeniedException surfaces as 500
        // (the prod chain translates to 403). Either way: not OK, service not called.
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    if (s == 200) throw new AssertionError("EVALUATEUR should not get 200, got " + s);
                });
        verify(userService, org.mockito.Mockito.never()).getAllUsers(org.mockito.ArgumentMatchers.any());
    }

    // -------------------------------------------------------------------------
    // Optional ?role= query param (#80 second AC)
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(authorities = "ROLE_RESPONSABLE_MATIERE:5")
    void getAllUsers_withRoleParam_passesFilterToService() throws Exception {
        // Picker case: responsable asks for just the evaluators.
        when(userService.getAllUsers(eq(RoleType.EVALUATEUR))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/users").param("role", "EVALUATEUR"))
                .andExpect(status().isOk());

        verify(userService).getAllUsers(RoleType.EVALUATEUR);
    }

    @Test
    @WithMockUser(authorities = "ROLE_SUPER_ADMIN")
    void getAllUsers_withoutRoleParam_passesNullToService() throws Exception {
        when(userService.getAllUsers(isNull())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk());

        verify(userService).getAllUsers(null);
    }

    // -------------------------------------------------------------------------
    // POST /{id}/roles — additive role grant
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(authorities = "ROLE_RESPONSABLE_MATIERE:5")
    void addRoles_responsable_returns200_andDelegatesToService() throws Exception {
        mockMvc.perform(post("/api/v1/users/2/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"role\":\"EVALUATEUR\"}]"))
                .andExpect(status().isOk());

        verify(userService).addRoles(eq(2L), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(authorities = "ROLE_EVALUATEUR")
    void addRoles_evaluateur_isDenied() throws Exception {
        // An EVALUATEUR must not be able to grant himself anything.
        mockMvc.perform(post("/api/v1/users/2/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"role\":\"SUPER_ADMIN\"}]"))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    if (s == 200) throw new AssertionError("EVALUATEUR should not get 200, got " + s);
                });
        verify(userService, org.mockito.Mockito.never()).addRoles(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(authorities = "ROLE_SUPER_ADMIN")
    void getAllUsers_withInvalidRoleParam_isRejected() throws Exception {
        // Unknown enum value triggers Spring's MethodArgumentTypeMismatchException.
        // Auth-service GEH doesn't currently handle it explicitly → falls through to 500.
        // What matters here: bad input doesn't silently reach the service with null.
        mockMvc.perform(get("/api/v1/users").param("role", "NOT_A_ROLE"))
                .andExpect(result -> {
                    int s = result.getResponse().getStatus();
                    if (s == 200) throw new AssertionError("invalid enum should not return 200, got " + s);
                });
        verify(userService, org.mockito.Mockito.never()).getAllUsers(org.mockito.ArgumentMatchers.any());
    }
}
