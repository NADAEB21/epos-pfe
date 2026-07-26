package tn.epos.scoring_service.dto;

import java.time.LocalDate;

/**
 * #147 — corps de {@code PATCH /api/lots/{id}/jour}. {@code jour == null} est un
 * effacement EXPLICITE (le lot repasse au jour unique de l'examen) — sémantique
 * que le PUT générique ne peut pas porter, son null signifiant « ne pas toucher »
 * (#215).
 */
public record ChangerJourRequest(LocalDate jour) {
}
