package tn.epos.exam_service;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
// @EnableJpaRepositories(basePackages = "tn.epos.exam_service.repositories")   //deactivated for pipline tests
// Schéma de sécurité déclaré globalement (aucune annotation par endpoint) : ajoute le
// bouton Authorize à Swagger UI pour tester les refus 401/403 avec un vrai jeton.
// Inerte en production : l'UI y est désactivée par drapeau et inaccessible par routage.
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP,
        scheme = "bearer", bearerFormat = "JWT")
@OpenAPIDefinition(
        security = @SecurityRequirement(name = "bearerAuth"),
        info = @Info(
        title = "EPOS — Examens, stations et grilles",
        version = "1.0",
        description = "Conception des examens : stations, grilles d'évaluation pondérées, critères et modèles réutilisables."))
public class ExamServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ExamServiceApplication.class, args);
	}

}
