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
 * (each already out of its grille's noteMax).
 *
 * <p>#361 (ADR-0030 D4) — the response now serves BOTH denominators, computed
 * from the local snapshots (scoring owns {@code note_max} since V19):
 * <ul>
 *   <li>{@code denominateurOriginal} — Σ {@code note_max} over the exam's
 *       snapshotted stations ({@code null} pre-V19);</li>
 *   <li>{@code totalDelibere}/{@code denominateurDelibere} — totals under the
 *       CURRENT barème de délibération, recomputed at read time over the intact
 *       snapshot (excluded stations leave both sums); {@code null} when no
 *       barème exists;</li>
 *   <li>{@code baremeVersion} — the applied version ({@code null} when none);
 *       its motif/author/operations are served by
 *       {@code GET /examen/{id}/bareme-deliberation}.</li>
 * </ul>
 * No stored value is rewritten — the délibéré pair is presentation only.
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
    List<StationScoreDTO> stations,
    Double denominateurOriginal,
    Double totalDelibere,
    Double denominateurDelibere,
    Integer baremeVersion
) {}
