package tn.epos.scoring_service;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
// Schéma de sécurité déclaré globalement (aucune annotation par endpoint) : ajoute le
// bouton Authorize à Swagger UI pour tester les refus 401/403 avec un vrai jeton.
// Inerte en production : l'UI y est désactivée par drapeau et inaccessible par routage.
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP,
        scheme = "bearer", bearerFormat = "JWT")
@OpenAPIDefinition(
        security = @SecurityRequirement(name = "bearerAuth"),
        info = @Info(
        title = "EPOS — Notation, rotations et résultats",
        version = "1.0",
        description = "Déroulement de l'examen : étudiants, lots, rotations, notations, réclamations et résultats délibérés."))
public class ScoringServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ScoringServiceApplication.class, args);
	}
}