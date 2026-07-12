package tn.epos.scoring_service.client;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Read-only projection of an exam-service Examen + its stations, fetched
 * cross-service for rotation generation. Carries only the fields the OSCE
 * round-robin needs; not persisted.
 *
 * @see ExamServiceClient#getExamForGeneration(Long)
 */
public record ExamGenerationView(
        Long examenId,
        LocalDate dateExamen,
        LocalTime heureDebut,
        LocalDateTime launchedAt,
        Integer dureeStationMin,
        Integer tempsBattementMin,
        Integer nbEtudiantsParStation,
        String statut,
        List<StationView> stations) {

    /** One station with its bound évaluateur(s). Ordered by {@code ordre}. */
    public record StationView(Long id, Integer ordre, List<Long> evaluateurIds) {
    }
}
