package tn.epos.exam_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.epos.exam_service.enums.StatutExamen;

import java.time.LocalDateTime;

/**
 * Vue MINIMALE d'un examen : son état d'exécution, rien d'autre.
 *
 * <p>Destinée aux appels service-à-service de scoring-service pour le compte d'un
 * ÉVALUATEUR (dashboard mobile) : celui-ci a besoin de savoir si l'examen tourne, s'il
 * est en pause, et la durée d'une station — mais n'a aucune raison de recevoir le détail
 * complet de l'examen ({@link ExamenResponse} expose les stations, le sujet, la matière…).
 *
 * <p><b>Pourquoi un DTO dédié plutôt qu'ouvrir {@code GET /api/examens/{id}} :</b> un
 * évaluateur n'est pas un responsable. On lui expose l'état opérationnel dont il a besoin,
 * et strictement rien de plus. Aucune donnée étudiant, aucune grille, aucun barème.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamTimingResponse {

    private Long id;

    /** BROUILLON | CONFIGURE | EN_COURS | TERMINE | ARCHIVE. */
    private StatutExamen statut;

    private boolean enPause;
    private LocalDateTime pausedAt;
    private Integer totalPauseSec;
    private LocalDateTime launchedAt;

    private Integer dureeStationMin;
    private Integer avertissementLeadSec;
}
