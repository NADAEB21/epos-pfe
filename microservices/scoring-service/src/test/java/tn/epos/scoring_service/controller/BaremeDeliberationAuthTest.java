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
import tn.epos.scoring_service.dto.BaremeDeliberationDTO;
import tn.epos.scoring_service.service.BaremeDeliberationService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-0030 (#361) — contrat d'autorisation + validation du barème de
 * délibération, même forme que {@code NotationReajustementAuthTest} (vraie
 * {@link SecurityConfig}, JWT HS256 signé à la main).
 *
 * Contrat : RESPONSABLE_MATIERE + SUPER_ADMIN écrivent/lisent ; ÉVALUATEUR
 * jamais (403, service jamais appelé — l'IA non plus n'a pas ce chemin,
 * ADR-0029 D2 : elle n'a simplement aucun jeton) ; sans token → 401 ; motif
 * vide → 400 sans que le service soit appelé.
 */
@WebMvcTest(controllers = BaremeDeliberationController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "jwt.secret=a-very-secure-32-char-secret-key",
        "app.cors.allowed-origins=http://localhost:4200"
})
@DisplayName("BaremeDeliberationController - autorisation & validation (ADR-0030, #361)")
class BaremeDeliberationAuthTest {

    private static final String SECRET = "a-very-secure-32-char-secret-key";
    private static final String URL = "/api/notations/examen/77/bareme-deliberation";
    private static final String BODY_OK =
            "{\"motif\":\"Critère litigieux exclu en délibération\",\"operations\":[]}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BaremeDeliberationService service;

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

    private static BaremeDeliberationDTO dto() {
        return new BaremeDeliberationDTO(1L, 77L, 1, "motif", 5L,
                LocalDateTime.now(), List.of());
    }

    @Test
    @DisplayName("RESPONSABLE_MATIERE peut écrire un barème → 201")
    void responsable_peutEcrire() throws Exception {
        when(service.creer(eq(77L), any())).thenReturn(dto());
        String token = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:1"));

        mockMvc.perform(post(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_OK))
                .andExpect(status().isCreated());

        verify(service).creer(eq(77L), any());
    }

    @Test
    @DisplayName("SUPER_ADMIN peut écrire un barème → 201")
    void admin_peutEcrire() throws Exception {
        when(service.creer(eq(77L), any())).thenReturn(dto());
        String token = jwtWith(List.of("ROLE_SUPER_ADMIN"));

        mockMvc.perform(post(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_OK))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("ÉVALUATEUR ne peut PAS écrire → 403, service jamais appelé")
    void evaluateur_estInterdit() throws Exception {
        String token = jwtWith(List.of("ROLE_EVALUATEUR"));

        mockMvc.perform(post(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_OK))
                .andExpect(status().isForbidden());

        verify(service, never()).creer(any(), any());
    }

    @Test
    @DisplayName("Sans token → 401")
    void sansToken_estNonAutorise() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_OK))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("motif vide → 400 (motif obligatoire), service jamais appelé")
    void motifVide_estRejete() throws Exception {
        String token = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:1"));
        String bodySansMotif = "{\"motif\":\"  \",\"operations\":[]}";

        mockMvc.perform(post(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(bodySansMotif))
                .andExpect(status().isBadRequest());

        verify(service, never()).creer(any(), any());
    }

    @Test
    @DisplayName("operations absentes → 400 (liste requise, vide = retour à l'origine)")
    void operationsAbsentes_estRejete() throws Exception {
        String token = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:1"));
        String bodySansOps = "{\"motif\":\"retour\"}";

        mockMvc.perform(post(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(bodySansOps))
                .andExpect(status().isBadRequest());

        verify(service, never()).creer(any(), any());
    }

    @Test
    @DisplayName("Historique : ÉVALUATEUR interdit → 403 ; RESPONSABLE autorisé → 200")
    void historique_estGardeParRole() throws Exception {
        when(service.historique(77L)).thenReturn(List.of());

        String eval = jwtWith(List.of("ROLE_EVALUATEUR"));
        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + eval))
                .andExpect(status().isForbidden());

        String resp = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:1"));
        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + resp))
                .andExpect(status().isOk());
    }
}
