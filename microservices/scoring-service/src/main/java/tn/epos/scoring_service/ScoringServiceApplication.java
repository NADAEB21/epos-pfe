package tn.epos.scoring_service;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(
        title = "EPOS — Notation, rotations et résultats",
        version = "1.0",
        description = "Déroulement de l'examen : étudiants, lots, rotations, notations, réclamations et résultats délibérés."))
public class ScoringServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ScoringServiceApplication.class, args);
	}
}