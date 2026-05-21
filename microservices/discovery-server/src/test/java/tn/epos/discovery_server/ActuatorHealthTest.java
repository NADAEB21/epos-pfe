package tn.epos.discovery_server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards that /actuator/health is reachable — docker-compose health probes
 * (#26) start the rest of the stack only once the registry reports healthy.
 *
 * Uses a real embedded server: the Eureka server's filter registrations break
 * @AutoConfigureMockMvc, so MockMvc cannot be used here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorHealthTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthEndpoint_isReachable() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
