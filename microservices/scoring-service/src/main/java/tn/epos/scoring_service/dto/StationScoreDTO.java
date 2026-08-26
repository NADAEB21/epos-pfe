package tn.epos.scoring_service.dto;

import tn.epos.scoring_service.entities.Notation;

/**
 * One student's score at one station, projected from a {@link Notation}.
 * Carries the cross-service {@code stationId}/{@code grilleId} (logical FKs into
 * exam_db) so the frontend can resolve the station name for the column header.
 *
 * <p>#361 (ADR-0030 D4) — the row now serves BOTH denominators:
 * <ul>
 *   <li>{@code maxOriginal} — the station's declared barème
 *       ({@code exam_grille_snapshot.note_max}); {@code null} for a pre-V19 exam
 *       with no snapshot (the frontend then falls back to the live grille, as
 *       the délibération screen already does);</li>
 *   <li>{@code scoreDelibere}/{@code maxDelibere} — the presentation-time
 *       recomputation under the CURRENT barème de délibération; {@code null}
 *       when no barème exists, when the station is excluded by it, or when the
 *       stored score is null. Raw values (« /15 ») — the « ×20/15 » reconversion
 *       is a display choice left to the UI (#363).</li>
 * </ul>
 */
public record StationScoreDTO(
    Long notationId,
    Long stationId,
    Long grilleId,
    Float score,
    Boolean verrouillee,
    Double maxOriginal,
    Float scoreDelibere,
    Double maxDelibere
) {
    public static StationScoreDTO fromEntity(Notation n) {
        return fromEntity(n, null, null, null);
    }

    public static StationScoreDTO fromEntity(
            Notation n, Double maxOriginal, Float scoreDelibere, Double maxDelibere) {
        return new StationScoreDTO(
            n.getId(),
            n.getStationId(),
            n.getGrilleId(),
            n.getScore_final(),
            n.getVerouillee(),
            maxOriginal,
            scoreDelibere,
            maxDelibere
        );
    }
}
