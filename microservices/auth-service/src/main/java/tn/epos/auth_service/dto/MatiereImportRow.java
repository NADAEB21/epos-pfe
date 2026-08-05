package tn.epos.auth_service.dto;

/**
 * #134 — une ligne d'import en lot. VOLONTAIREMENT sans contrainte Bean
 * Validation : une ligne invalide ne doit pas faire échouer tout l'envoi en
 * 400 générique — elle doit produire un verdict ERREUR sur SA ligne, et les
 * lignes valides doivent passer (même contrat que l'import d'étudiants).
 */
public record MatiereImportRow(String code, String libelle) {}
