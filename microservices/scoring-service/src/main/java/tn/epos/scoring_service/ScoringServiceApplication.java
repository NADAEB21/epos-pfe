package tn.epos.scoring_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
// On force Spring à scanner les packages spécifiques pour éviter les erreurs de détection
@EnableJpaRepositories(basePackages = "tn.epos.scoring_service.repositories")
@EntityScan(basePackages = "tn.epos.scoring_service.entities")
public class ScoringServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ScoringServiceApplication.class, args);
	}
}