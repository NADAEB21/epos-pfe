package tn.epos.scoring_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
// Cette ligne force Hibernate à créer les tables pour le test, ce qui évite l'erreur "missing table"
@TestPropertySource(properties = {
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.datasource.url=jdbc:h2:mem:testdb",
		"spring.datasource.driver-class-name=org.h2.Driver"
})
class ScoringServiceApplicationTests {

	@Test
	void contextLoads() {
		// Test de fumée pour vérifier que le contexte Spring démarre
	}
}
