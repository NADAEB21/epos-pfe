package tn.epos.exam_service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import tn.epos.exam_service.controllers.GrilleTemplateController;
import tn.epos.exam_service.dto.request.GrilleTemplateRequest;
import tn.epos.exam_service.services.GrilleTemplateService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GrilleTemplateController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "jwt.secret=a-very-secure-32-char-secret-key", // Exactly 32 bytes for HS256
        "app.cors.allowed-origins=http://localhost:4200"
})
@DisplayName("GrilleTemplateController — SUPER_ADMIN-only template CRUD (#96)")
class GrilleTemplateAuthorizationTest {

    // Must match the property above
    private static final String SECRET = "a-very-secure-32-char-secret-key";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GrilleTemplateService templateService;

    private static String jwtWith(List<String> authorities) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user@epos.tn")
                .claim("authorities", authorities)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        // Now HS256 here matches the 32-byte secret expectation in HmacJwtDecoders
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static String creerBody() throws Exception {
        GrilleTemplateRequest req = new GrilleTemplateRequest();
        req.setNom("Template X");
        req.setNoteMax(20.0);
        return new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(req);
    }

    @Test
    @DisplayName("POST /api/templates/grilles: SUPER_ADMIN allowed")
    void creer_superAdmin_allowed() throws Exception {
        String token = jwtWith(List.of("ROLE_SUPER_ADMIN"));
        mockMvc.perform(post("/api/templates/grilles")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerBody()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("POST /api/templates/grilles: RESPONSABLE_MATIERE forbidden")
    void creer_responsableMatiere_forbidden() throws Exception {
        String token = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:5"));
        mockMvc.perform(post("/api/templates/grilles")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creerBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/templates/grilles/{id}: SUPER_ADMIN allowed")
    void supprimer_superAdmin_allowed() throws Exception {
        String token = jwtWith(List.of("ROLE_SUPER_ADMIN"));
        mockMvc.perform(delete("/api/templates/grilles/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DELETE /api/templates/grilles/{id}: RESPONSABLE_MATIERE forbidden")
    void supprimer_responsableMatiere_forbidden() throws Exception {
        String token = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:5"));
        mockMvc.perform(delete("/api/templates/grilles/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/templates/grilles: RESPONSABLE_MATIERE still allowed (browse library)")
    void lister_responsableMatiere_allowed() throws Exception {
        String token = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:5"));
        mockMvc.perform(get("/api/templates/grilles")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }
}