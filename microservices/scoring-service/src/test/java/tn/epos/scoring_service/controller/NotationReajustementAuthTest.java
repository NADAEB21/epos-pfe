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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.scoring_service.config.SecurityConfig;
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.service.NotationReajustementService;
import tn.epos.scoring_service.service.NotationService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-0013 Part 2 authorization + validation contract for the réajustement
 * endpoints. Uses the real {@link SecurityConfig} (so {@code @PreAuthorize} is
 * actually enforced against a signed JWT) — same shape as
 * {@code SecurityConfigAuthorizationTest}.
 *
 * Contract: RESPONSABLE_MATIERE + SUPER_ADMIN may réajuster; ÉVALUATEUR may NOT
 * (403); no token → 401; blank motif → 400.
 */
@WebMvcTest(controllers = NotationController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "jwt.secret=a-very-secure-32-char-secret-key",
        "app.cors.allowed-origins=http://localhost:4200"
})
@DisplayName("NotationController - réajustement audité : autorisation & validation (#23/#135)")
class NotationReajustementAuthTest {

    private static final String SECRET = "a-very-secure-32-char-secret-key";
    private static final String BODY_OK = "{\"itemId\":null,\"nouvelleValeur\":12.5,\"motif\":\"Réclamation étudiant\"}";

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
    @DisplayName("RESPONSABLE_MATIERE peut réajuster → 200")
    void responsable_peutReajuster() throws Exception {
        when(service.findById(1L)).thenReturn(Optional.of(new Notation()));
        String token = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:5"));

        mockMvc.perform(post("/api/notations/1/reajustement")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_OK))
                .andExpect(status().isOk());

        verify(reajustementService).reajuster(eq(1L), any());
    }

    @Test
    @DisplayName("SUPER_ADMIN peut réajuster → 200")
    void admin_peutReajuster() throws Exception {
        when(service.findById(1L)).thenReturn(Optional.of(new Notation()));
        String token = jwtWith(List.of("ROLE_SUPER_ADMIN"));

        mockMvc.perform(post("/api/notations/1/reajustement")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_OK))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ÉVALUATEUR ne peut PAS réajuster → 403 (le canal n'est pas le sien)")
    void evaluateur_estInterdit() throws Exception {
        String token = jwtWith(List.of("ROLE_EVALUATEUR"));

        mockMvc.perform(post("/api/notations/1/reajustement")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_OK))
                .andExpect(status().isForbidden());

        verify(reajustementService, never()).reajuster(any(), any());
    }

    @Test
    @DisplayName("Sans token → 401")
    void sansToken_estNonAutorise() throws Exception {
        mockMvc.perform(post("/api/notations/1/reajustement")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_OK))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("motif vide → 400 (motif obligatoire), le service n'est jamais appelé")
    void motifVide_estRejete() throws Exception {
        String token = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:5"));
        String bodySansMotif = "{\"itemId\":null,\"nouvelleValeur\":12.5,\"motif\":\"  \"}";

        mockMvc.perform(post("/api/notations/1/reajustement")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(bodySansMotif))
                .andExpect(status().isBadRequest());

        verify(reajustementService, never()).reajuster(any(), any());
    }

    @Test
    @DisplayName("Historique : ÉVALUATEUR interdit → 403 ; RESPONSABLE autorisé → 200")
    void historique_estGardeParRole() throws Exception {
        when(reajustementService.historique(1L)).thenReturn(List.of());

        String eval = jwtWith(List.of("ROLE_EVALUATEUR"));
        mockMvc.perform(get("/api/notations/1/reajustements")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + eval))
                .andExpect(status().isForbidden());

        String resp = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:5"));
        mockMvc.perform(get("/api/notations/1/reajustements")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + resp))
                .andExpect(status().isOk());
    }
}
