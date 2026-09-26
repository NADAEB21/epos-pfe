package tn.epos.auth_service;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
// Schéma de sécurité déclaré globalement (aucune annotation par endpoint) : ajoute le
// bouton Authorize à Swagger UI pour tester les refus 401/403 avec un vrai jeton.
// Inerte en production : l'UI y est désactivée par drapeau et inaccessible par routage.
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP,
        scheme = "bearer", bearerFormat = "JWT")
@OpenAPIDefinition(
        security = @SecurityRequirement(name = "bearerAuth"),
        info = @Info(
        title = "EPOS — Authentification et comptes",
        version = "1.0",
        description = "Connexion, jetons, rôles portés par matière, cycle de vie des comptes et catalogue des matières."))
public class AuthServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuthServiceApplication.class, args);
	}

}
