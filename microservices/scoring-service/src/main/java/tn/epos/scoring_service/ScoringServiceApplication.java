package tn.epos.scoring_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
// @EnableJpaRepositories(basePackages = "tn.epos.scoring_service.repositories")   //deactivated for pipline tests
// @EntityScan(basePackages = "tn.epos.scoring_service.entities")    //deactivated for pipline tests
public class ScoringServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ScoringServiceApplication.class, args);
	}
}