package tn.epos.scoring_service.controller;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.scoring_service.config.SecurityConfig;
import tn.epos.scoring_service.service.NotationReajustementService;
import tn.epos.scoring_service.service.NotationService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #355 — contrat d'autorisation de GET /api/notations/examen/{id}/grilles,
 * contre le VRAI {@link SecurityConfig} (même forme que
 * {@link NotationReajustementAuthTest}) : responsable/admin lisent le barème
 * de délibération, l'évaluateur NON (comme /results — la délibération est le
 * territoire du responsable), sans jeton → 401. Le périmètre de MATIÈRE est
 * dans le service (garde #274), testé par NotationServiceTest.
 */
@WebMvcTest(controllers = NotationController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "jwt.secret=a-very-secure-32-char-secret-key",
        "app.cors.allowed-origins=http://localhost:4200"
})
@DisplayName("NotationController - /examen/{id}/grilles : autorisation (#355)")
class NotationExamenGrillesAuthTest {

    private static final String SECRET = "a-very-secure-32-char-secret-key";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotationService service;

    @MockBean
    private NotationReajustementService reajustementService;

    private static String jwtWith(List<String> authorities) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user@epos.tn")
                .claim("userId", 77)
                .claim("authorities", authorities)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    @Test
    @DisplayName("RESPONSABLE_MATIERE peut lire → 200")
    void responsable_peutLire() throws Exception {
        when(service.getGrillesSnapshotByExamen(77L)).thenReturn(List.of());
        String token = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:5"));

        mockMvc.perform(get("/api/notations/examen/77/grilles")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        verify(service).getGrillesSnapshotByExamen(77L);
    }

    @Test
    @DisplayName("SUPER_ADMIN peut lire → 200")
    void admin_peutLire() throws Exception {
        when(service.getGrillesSnapshotByExamen(77L)).thenReturn(List.of());
        String token = jwtWith(List.of("ROLE_SUPER_ADMIN"));

        mockMvc.perform(get("/api/notations/examen/77/grilles")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ÉVALUATEUR ne peut PAS → 403 (la délibération est le territoire du responsable)")
    void evaluateur_estInterdit() throws Exception {
        String token = jwtWith(List.of("ROLE_EVALUATEUR"));

        mockMvc.perform(get("/api/notations/examen/77/grilles")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());

        verify(service, never()).getGrillesSnapshotByExamen(any());
    }

    @Test
    @DisplayName("Sans jeton → 401")
    void sansJeton_401() throws Exception {
        mockMvc.perform(get("/api/notations/examen/77/grilles"))
                .andExpect(status().isUnauthorized());

        verify(service, never()).getGrillesSnapshotByExamen(any());
    }
}
