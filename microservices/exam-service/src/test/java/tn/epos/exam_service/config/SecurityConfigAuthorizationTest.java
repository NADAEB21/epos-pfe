package tn.epos.exam_service.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.exam_service.controllers.ExamenController;
import tn.epos.exam_service.dto.response.ExamenResponse;
import tn.epos.exam_service.services.ExamenService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end security test for issue #58: a real HS256-signed JWT flows through
 * the production {@link SecurityConfig} — decoder, {@link ScopedAuthoritiesConverter}
 * and the class-level {@code @PreAuthorize} on {@link ExamenController}.
 *
 * <p>Same {@code @WebMvcTest} slice as {@code SecurityConfigCorsTest} (no JPA /
 * Flyway / Eureka). Proper Testcontainers integration is tracked under #28.
 */
@WebMvcTest(controllers = ExamenController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-not-used-in-production-min-32-bytes-please",
        "app.cors.allowed-origins=http://localhost:4200"
})
@DisplayName("SecurityConfig - @PreAuthorize vs scoped JWT authorities (#58)")
class SecurityConfigAuthorizationTest {

    // Must match the jwt.secret above so the minted token verifies against the decoder.
    private static final String SECRET =
            "test-secret-not-used-in-production-min-32-bytes-please";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExamenService examenService;

    @BeforeEach
    void stubService() {
        Page<ExamenResponse> empty = new PageImpl<>(List.of());
        when(examenService.listerTous(any(Pageable.class))).thenReturn(empty);
    }

    /** Mints a valid HS256 JWT with the given {@code authorities} claim. */
    private static String jwtWith(List<String> authorities) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user@epos.tn")
                .claim("authorities", authorities)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    @Test
    @DisplayName("RESPONSABLE_MATIERE with scoped authority ROLE_..:5 can list examens (the #58 fix)")
    void scopedResponsableMatiere_canListExamens() throws Exception {
        String token = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:5"));

        mockMvc.perform(get("/api/examens")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SUPER_ADMIN (global authority) can list examens")
    void superAdmin_canListExamens() throws Exception {
        String token = jwtWith(List.of("ROLE_SUPER_ADMIN"));

        mockMvc.perform(get("/api/examens")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("EVALUATEUR is forbidden — the converter must not over-grant")
    void evaluateur_isForbidden() throws Exception {
        String token = jwtWith(List.of("ROLE_EVALUATEUR"));

        mockMvc.perform(get("/api/examens")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("request without a token is unauthorized")
    void noToken_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/examens"))
                .andExpect(status().isUnauthorized());
    }
}
