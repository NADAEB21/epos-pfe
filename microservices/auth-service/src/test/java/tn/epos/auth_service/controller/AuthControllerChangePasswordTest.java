package tn.epos.auth_service.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.auth_service.config.JwtAuthenticationDetails;
import tn.epos.auth_service.config.JwtAuthenticationFilter;
import tn.epos.auth_service.dto.ChangePasswordRequest;
import tn.epos.auth_service.service.AuthService;
import tn.epos.auth_service.service.JwtService;
import tn.epos.auth_service.service.UserDetailsServiceImpl;
import tn.epos.auth_service.service.UserService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /api/v1/auth/change-password — couverture au niveau contrôleur (PR #180).
 *
 * <p>Ce que ces tests verrouillent, et que le test de service ne peut pas couvrir :
 * <ul>
 *   <li><b>L'userId vient du JWT, JAMAIS du corps de la requête.</b> C'est la propriété
 *       de sécurité centrale : sinon n'importe qui pourrait changer le mot de passe
 *       d'autrui en postant un autre id.</li>
 *   <li>La validation du DTO (politique de mot de passe) rejette en 400 <b>avant</b>
 *       d'atteindre le service.</li>
 *   <li>Un mot de passe actuel faux remonte en 401, pas en 500.</li>
 * </ul>
 *
 * <p>Filtres désactivés ({@code addFilters = false}), comme {@code UserControllerTest} :
 * monter toute la chaîne JWT pour un test de contrôleur n'apporte rien.
 *
 * <p><b>Piège :</b> le paramètre {@code Authentication} du contrôleur est résolu depuis le
 * PRINCIPAL de la requête, pas depuis le SecurityContextHolder. Sans filtres, ni
 * {@code SecurityContextHolder.setAuthentication(...)} ni le post-processor
 * {@code authentication(...)} ne le renseignent → le contrôleur reçoit {@code null} et
 * part en NPE/500. Il faut {@code .principal(...)}.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class AuthControllerChangePasswordTest {

    private static final String URL = "/api/v1/auth/change-password";
    private static final Long   USER_ID_FROM_TOKEN = 42L;

    @Autowired private MockMvc mockMvc;

    @MockBean private AuthService authService;
    // AuthController injecte aussi UserService (pour GET /auth/me).
    @MockBean private UserService userService;

    // Tirés par l'auto-scan (JwtAuthenticationFilter est un @Component).
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean private JwtService jwtService;
    @MockBean private UserDetailsServiceImpl userDetailsService;

    /**
     * L'appelant est l'utilisateur 42, et cet id est porté par le JWT (via
     * {@link JwtAuthenticationDetails}) — jamais par le corps de la requête.
     */
    private Authentication jwtAuthOfUser42() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "user@test.com", "n/a", List.of());
        auth.setDetails(new JwtAuthenticationDetails(
                mock(WebAuthenticationDetails.class), USER_ID_FROM_TOKEN));
        return auth;
    }

    private String body(String current, String next) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
    }

    @Test
    void changePassword_devraitUtiliserLUserIdDuJwt_pasCeluiDuCorps() throws Exception {
        // Le corps essaie de se faire passer pour l'utilisateur 999 : ça doit être ignoré.
        String malicious = "{\"userId\":999,\"currentPassword\":\"Eval@1234\",\"newPassword\":\"NewPass@99\"}";

        mockMvc.perform(put(URL).principal(jwtAuthOfUser42()).contentType(MediaType.APPLICATION_JSON).content(malicious))
                .andExpect(status().isOk());

        // Le service est appelé avec 42 (le JWT), jamais 999 (le corps).
        verify(authService).changePassword(eq(USER_ID_FROM_TOKEN), any(ChangePasswordRequest.class));
        verify(authService, never()).changePassword(eq(999L), any(ChangePasswordRequest.class));
    }

    @Test
    void changePassword_succes_devraitRetourner200() throws Exception {
        mockMvc.perform(put(URL).principal(jwtAuthOfUser42()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Eval@1234", "NewPass@99")))
                .andExpect(status().isOk());

        verify(authService).changePassword(eq(USER_ID_FROM_TOKEN), any(ChangePasswordRequest.class));
    }

    @Test
    void changePassword_motDePasseFaible_devraitRetourner400SansAppelerLeService() throws Exception {
        // "weak" : trop court, pas de majuscule, pas de chiffre.
        mockMvc.perform(put(URL).principal(jwtAuthOfUser42()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Eval@1234", "weak")))
                .andExpect(status().isBadRequest());

        verify(authService, never()).changePassword(any(), any());
    }

    @Test
    void changePassword_sansMajusculeNiChiffre_devraitRetourner400() throws Exception {
        mockMvc.perform(put(URL).principal(jwtAuthOfUser42()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Eval@1234", "motdepasselong")))
                .andExpect(status().isBadRequest());

        verify(authService, never()).changePassword(any(), any());
    }

    @Test
    void changePassword_motDePasseActuelIncorrect_devraitRetourner401() throws Exception {
        doThrow(new BadCredentialsException("Mot de passe actuel incorrect"))
                .when(authService).changePassword(eq(USER_ID_FROM_TOKEN), any(ChangePasswordRequest.class));

        mockMvc.perform(put(URL).principal(jwtAuthOfUser42()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("mauvais", "NewPass@99")))
                .andExpect(status().isUnauthorized());
    }
}
