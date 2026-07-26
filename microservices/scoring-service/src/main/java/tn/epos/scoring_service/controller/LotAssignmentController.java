package tn.epos.scoring_service.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.PresenceBulkRequest;
import tn.epos.scoring_service.dto.PresenceResult;
import tn.epos.scoring_service.dto.RepartitionResult;
import tn.epos.scoring_service.dto.DemarrageResult;
import tn.epos.scoring_service.service.LotAssignmentService;
import tn.epos.scoring_service.service.LotDemarrageService;

/**
 * Two-phase lot workflow endpoints. Mounted under {@code /api/lots} so the
 * gateway routes them to scoring-service ({@code /api/v1/lots/**}); kept apart
 * from {@link LotController}'s CRUD so the orchestration (cross-service exam read,
 * partitioning, presence) stays cohesive.
 *
 * <ul>
 *   <li>{@code POST /examens/{id}/repartir} — Phase 1, CONFIGURE-time: partition
 *       the roster into waves.</li>
 *   <li>{@code PATCH /{lotId}/presence} — Phase 2, exam day: mark a wave present
 *       and unlock its rotation generation.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/lots")
@RequiredArgsConstructor
public class LotAssignmentController {

    private final LotAssignmentService lotAssignmentService;
    private final LotDemarrageService  lotDemarrageService;

    @PostMapping("/examens/{examenId}/repartir")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<RepartitionResult>> repartir(@PathVariable Long examenId) {
        RepartitionResult result = lotAssignmentService.repartir(examenId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Étudiants répartis en lots", result));
    }

    /**
     * #185 — « Présence & démarrer » : LE bouton du conducteur jour J. Présence (absents en
     * option) + génération des rotations en UNE transaction ; l'ouverture de vague reste
     * déléguée à ADR-0014-B (première vague auto, suivantes via « Ouvrir le lot N »).
     * Si la génération refuse (garde #188, examen non lancé…), la présence est annulée avec
     * elle — jamais d'état intermédiaire à comprendre.
     */
    @PostMapping("/{lotId}/presence-et-demarrer")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<DemarrageResult>> presenceEtDemarrer(@PathVariable Long lotId,
            @RequestBody(required = false) PresenceBulkRequest body) {
        DemarrageResult result = lotDemarrageService.presenceEtDemarrer(
                lotId, body == null ? null : body.absents());
        return ResponseEntity.ok(ApiResponse.ok("Lot démarré : présence enregistrée, circuit généré.", result));
    }

    @PatchMapping("/{lotId}/presence")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<PresenceResult>> markPresence(@PathVariable Long lotId,
            @RequestBody(required = false) PresenceBulkRequest body) {
        PresenceResult result = lotAssignmentService.markPresence(
                lotId, body == null ? null : body.absents());
        return ResponseEntity.ok(ApiResponse.ok("Présence du lot enregistrée", result));
    }
}
