package tn.epos.scoring_service.dto;

import java.util.List;

/**
 * One student's aggregated result for a whole exam (issue #90). Computed on the
 * fly — no {@code ExamenResult} entity — by joining
 * Notation → RotationAssignment → ExamenParticipation → Etudiant for one
 * {@code examenId}, then grouping the per-station {@link StationScoreDTO}s by
 * participation.
 *
 * <p>{@code totalScore} is the plain sum of the per-station {@code score_final}s
 * (each already out of its grille's noteMax). The frontend computes the
 * {@code /max} denominator + class rank from the grille noteMax it fetches
 * separately — scoring-service does not own the grille definitions.
 *
 * <p>Field names are camelCase here (mirroring the Rotation/Assignment DTO
 * convention), NOT the snake_case of the Etudiant/Participation DTOs, so the
 * frontend model for this endpoint must use camelCase.
 */
public record ExamenResultDTO(
    Long participationId,
    Long etudiantId,
    String numeroInscription,
    String nom,
    String prenom,
    String numEchantillon,
    Double totalScore,
    int stationsNotees,
    List<StationScoreDTO> stations
) {}
