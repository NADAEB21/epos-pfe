package tn.epos.scoring_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.scoring_service.controller.NotationController;
import tn.epos.scoring_service.service.NotationService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// @WebMvcTest loads only the MVC + security slice (no JPA / Flyway / Eureka / DB).
// Avoids the H2 in-memory collision with ScoringServiceApplicationTests' shared
// mem:testdb (see PR notes; proper fix is issue #28 — Testcontainers).
@WebMvcTest(controllers = NotationController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-not-used-in-production-min-32-bytes-please",
        "app.cors.allowed-origins=http://localhost:4200,https://epos.example.com"
})
class SecurityConfigCorsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotationService notationService;

    @Test
    void preflight_fromAllowedOrigin_returns200WithCorsHeaders() throws Exception {
        mockMvc.perform(options("/api/notations")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Content-Type, Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"))
                .andExpect(header().exists("Access-Control-Allow-Methods"))
                .andExpect(header().exists("Access-Control-Allow-Headers"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void preflight_fromSecondAllowedOrigin_returns200WithCorsHeaders() throws Exception {
        mockMvc.perform(options("/api/notations")
                        .header("Origin", "https://epos.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://epos.example.com"));
    }

    @Test
    void preflight_fromDisallowedOrigin_isRejected() throws Exception {
        mockMvc.perform(options("/api/notations")
                        .header("Origin", "http://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
