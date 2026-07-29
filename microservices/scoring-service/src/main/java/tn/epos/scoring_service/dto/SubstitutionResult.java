package tn.epos.scoring_service.dto;

/**
 * ADR-0017 — bilan d'une suppléance.
 *
 * <p>{@code rotationsTransferees} et {@code rotationsConservees} sont renvoyés
 * séparément parce que c'est la question que se pose le responsable : « le
 * travail déjà fait reste-t-il au nom de celui qui l'a fait ? ». Oui — seules
 * les rotations non terminées changent de main.
 */
public record SubstitutionResult(
    Long lotId,
    Long stationId,
    Long ancienEvaluateur,
    Long nouvelEvaluateur,
    int rotationsTransferees,
    int rotationsConservees,
    String message
) {}
