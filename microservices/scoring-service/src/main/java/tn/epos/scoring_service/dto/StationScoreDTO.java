package tn.epos.scoring_service.dto;

import tn.epos.scoring_service.entities.Notation;

/**
 * One student's score at one station, projected from a {@link Notation}.
 * Carries the cross-service {@code stationId}/{@code grilleId} (logical FKs into
 * exam_db) so the frontend can resolve the station name + grille noteMax for the
 * column header and the {@code /max} denominator — scoring-service holds neither.
 */
public record StationScoreDTO(
    Long notationId,
    Long stationId,
    Long grilleId,
    Float score,
    Boolean verrouillee
) {
    public static StationScoreDTO fromEntity(Notation n) {
        return new StationScoreDTO(
            n.getId(),
            n.getStationId(),
            n.getGrilleId(),
            n.getScore_final(),
            n.getVerouillee()
        );
    }
}
