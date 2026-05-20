package tn.epos.scoring_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// Uses application-test.properties (H2 with DB_CLOSE_DELAY=-1, datasource
// credentials Flyway reuses, Eureka disabled, test jwt.secret). The former
// inline @TestPropertySource set an incomplete H2 config that collided with
// other @SpringBootTest classes sharing the in-memory database.
@SpringBootTest
@ActiveProfiles("test")
class ScoringServiceApplicationTests {

	@Test
	void contextLoads() {
		// Smoke test: the Spring context starts.
	}
}
