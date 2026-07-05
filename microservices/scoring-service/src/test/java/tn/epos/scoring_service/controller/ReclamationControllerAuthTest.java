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
import tn.epos.scoring_service.dto.ReclamationDTO;
import tn.epos.scoring_service.entities.ReclamationStatus;
import tn.epos.scoring_service.service.ReclamationService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization + validation contract for the réclamation register (#136).
 * Real {@link SecurityConfig} so {@code @PreAuthorize} is enforced against a
 * signed JWT — same shape as {@code NotationReajustementAuthTest}.
 *
 * Contract: RESPONSABLE_MATIERE + SUPER_ADMIN may file/resolve/read; ÉVALUATEUR
 * may NOT (403); no token → 401; blank objet → 400.
 */
@WebMvcTest(controllers = ReclamationController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "jwt.secret=a-very-secure-32-char-secret-key",
        "app.cors.allowed-origins=http://localhost:4200"
})
@DisplayName("ReclamationController — autorisation & validation (#136)")
class ReclamationControllerAuthTest {

    private static final String SECRET = "a-very-secure-32-char-secret-key";
    private static final String BODY_OK =
            "{\"examenId\":9,\"participationId\":3,\"notationId\":5,\"objet\":\"Conteste la note\"}";
    private static final String RESOLVE_OK =
            "{\"statut\":\"REJETEE\",\"reponse\":\"Note conforme au barème\",\"adjustmentId\":null}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReclamationService service;

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

    private static ReclamationDTO dummy() {
        return new ReclamationDTO(1L, 9L, 3L, 5L, "Conteste la note",
                ReclamationStatus.EN_ATTENTE, null, null, 77L, LocalDateTime.now(), null, null);
    }

    @Test
    @DisplayName("RESPONSABLE_MATIERE peut déposer une réclamation → 201")
    void responsable_peutDeposer() throws Exception {
        when(service.creer(any())).thenReturn(dummy());
        String token = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:5"));

        mockMvc.perform(post("/api/reclamations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_OK))
                .andExpect(status().isCreated());

        verify(service).creer(any());
    }

    @Test
    @DisplayName("SUPER_ADMIN peut déposer → 201")
    void admin_peutDeposer() throws Exception {
        when(service.creer(any())).thenReturn(dummy());
        String token = jwtWith(List.of("ROLE_SUPER_ADMIN"));

        mockMvc.perform(post("/api/reclamations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_OK))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("ÉVALUATEUR ne peut PAS déposer → 403 (registre du responsable)")
    void evaluateur_estInterdit() throws Exception {
        String token = jwtWith(List.of("ROLE_EVALUATEUR"));

        mockMvc.perform(post("/api/reclamations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_OK))
                .andExpect(status().isForbidden());

        verify(service, never()).creer(any());
    }

    @Test
    @DisplayName("Sans token → 401")
    void sansToken_estNonAutorise() throws Exception {
        mockMvc.perform(post("/api/reclamations")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_OK))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("objet vide → 400 (objet obligatoire), le service n'est jamais appelé")
    void objetVide_estRejete() throws Exception {
        String token = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:5"));
        String bodySansObjet =
                "{\"examenId\":9,\"participationId\":3,\"notationId\":5,\"objet\":\"  \"}";

        mockMvc.perform(post("/api/reclamations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(bodySansObjet))
                .andExpect(status().isBadRequest());

        verify(service, never()).creer(any());
    }

    @Test
    @DisplayName("Résolution : ÉVALUATEUR interdit → 403 ; RESPONSABLE autorisé → 200")
    void resoudre_estGardeParRole() throws Exception {
        when(service.resoudre(any(), any())).thenReturn(dummy());

        String eval = jwtWith(List.of("ROLE_EVALUATEUR"));
        mockMvc.perform(patch("/api/reclamations/1/resoudre")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + eval)
                        .contentType(MediaType.APPLICATION_JSON).content(RESOLVE_OK))
                .andExpect(status().isForbidden());

        String resp = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:5"));
        mockMvc.perform(patch("/api/reclamations/1/resoudre")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + resp)
                        .contentType(MediaType.APPLICATION_JSON).content(RESOLVE_OK))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Liste par examen : ÉVALUATEUR interdit → 403 ; RESPONSABLE autorisé → 200")
    void liste_estGardeParRole() throws Exception {
        when(service.listerParExamen(9L)).thenReturn(List.of());

        String eval = jwtWith(List.of("ROLE_EVALUATEUR"));
        mockMvc.perform(get("/api/reclamations/examen/9")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + eval))
                .andExpect(status().isForbidden());

        String resp = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:5"));
        mockMvc.perform(get("/api/reclamations/examen/9")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + resp))
                .andExpect(status().isOk());
    }
}
