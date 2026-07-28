package tn.epos.scoring_service.config;

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
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.scoring_service.controller.EtudiantController;
import tn.epos.scoring_service.controller.ExamenParticipationController;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.service.EtudiantService;
import tn.epos.scoring_service.service.ExamenParticipationService;
import tn.epos.scoring_service.repositories.IEtudiantRepository;
import tn.epos.scoring_service.repositories.ILotRepository;

import java.nio.charset.StandardCharsets;
import java.time.Clock; // #ADR-0010 Added
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {EtudiantController.class, ExamenParticipationController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "jwt.secret=a-very-secure-32-char-secret-key",
        "app.cors.allowed-origins=http://localhost:4200"
})
@DisplayName("SecurityConfig - @PreAuthorize vs scoped JWT authorities (#46)")
class SecurityConfigAuthorizationTest {

    private static final String SECRET = "a-very-secure-32-char-secret-key";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EtudiantService etudiantService;

    @MockBean
    private ExamenParticipationService participationService;

    @MockBean
    private IEtudiantRepository etudiantRepository;

    @MockBean
    private ILotRepository lotRepository;

    @MockBean
    private Clock clock; // #ADR-0010 Mocked to prevent context failure

    @BeforeEach
    void stubService() {
        when(etudiantService.getAllEtudiants()).thenReturn(List.of());
        // Required for HS256 validation logic in some Spring Security versions
        when(clock.getZone()).thenReturn(java.time.ZoneId.of("Africa/Tunis"));
        when(clock.instant()).thenReturn(Instant.now());
    }

    private static String jwtWith(List<String> authorities) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("user@epos.tn")
                .claim("userId", 7L) // Added userId claim for ADR-0007 compliance
                .claim("authorities", authorities)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .build();
        
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    @Test
    @DisplayName("RESPONSABLE_MATIERE with scoped authority ROLE_..:5 can list etudiants (the #46 fix)")
    void scopedResponsableMatiere_canListEtudiants() throws Exception {
        String token = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:5"));

        mockMvc.perform(get("/api/etudiants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("EVALUATEUR (global authority) can list etudiants")
    void evaluateur_canListEtudiants() throws Exception {
        String token = jwtWith(List.of("ROLE_EVALUATEUR"));

        mockMvc.perform(get("/api/etudiants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("scoped RESPONSABLE_MATIERE is forbidden on a SUPER_ADMIN-only endpoint")
    void scopedResponsableMatiere_cannotDeleteEtudiant() throws Exception {
        String token = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:5"));

        mockMvc.perform(delete("/api/etudiants/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("RESPONSABLE_MATIERE can DELETE a participation")
    void scopedResponsableMatiere_canDeleteParticipation() throws Exception {
        // Ensure the entity is instantiated correctly
        ExamenParticipation p = new ExamenParticipation();
        p.setId(1L);
        when(participationService.getById(1L)).thenReturn(Optional.of(p));
        
        String token = jwtWith(List.of("ROLE_RESPONSABLE_MATIERE:5"));

        mockMvc.perform(delete("/api/participations/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("request without a token is unauthorized")
    void noToken_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/etudiants"))
                .andExpect(status().isUnauthorized());
    }
}