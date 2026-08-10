package tn.epos.scoring_service.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.GenerationResult;
import tn.epos.scoring_service.dto.ResetRotationsResult;
import tn.epos.scoring_service.service.RotationGenerationService;

/**
 * Per-lot rotation generation endpoint (Phase 2 — exam day).
 *
 * <p>Mounted under {@code /api/rotations} so it routes to scoring-service at the
 * gateway ({@code /api/v1/rotations/**}). It is deliberately NOT under
 * {@code /api/examens/**}, which the gateway routes to exam-service.
 *
 * <p>Generation is now scoped to a single lot and gated to exam day: the exam
 * must be EN_COURS and the lot's presence must already be marked.
 *
 * <p><b>Périmètre de matière : désormais LOCAL (#274).</b> Il l'était auparavant
 * <i>cross-service</i> — la lecture de l'examen via l'appel exam-service portant le JWT de
 * l'appelant refusait les examens hors matière. Correct, mais l'autorisation tombait alors avec
 * exam-service, sur un chemin du jour J qu'ADR-0015 veut précisément indépendant. Le contrôle est
 * maintenant fait avant l'appel distant, sur la matière figée localement.
 */
@RestController
@RequestMapping("/api/rotations")
@RequiredArgsConstructor
public class RotationGenerationController {

    private final RotationGenerationService generationService;

    @PostMapping("/lots/{lotId}/generer")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<GenerationResult>> genererPourLot(@PathVariable Long lotId) {
        GenerationResult result = generationService.generateForLot(lotId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Rotations du lot générées avec succès", result));
    }

    /**
     * Réinitialise le planning généré d'un examen — moitié « données » du reset #183.
     * Purge les rotations/groupes de tous les lots (garde-fou #188 : refusé si une
     * notation existe ; scope matière enforced cross-service). Le passage
     * EN_COURS → CONFIGURE est fait ensuite par l'appelant via exam-service.
     *
     * <p>Reste sous {@code /api/rotations} (→ scoring-service au gateway), PAS sous
     * {@code /api/examens} (→ exam-service).
     */
    @PostMapping("/examens/{examenId}/reset")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<ResetRotationsResult>> resetPourExamen(@PathVariable Long examenId) {
        ResetRotationsResult result = generationService.resetRotationsForExam(examenId);
        return ResponseEntity.ok(
                ApiResponse.ok("Planning de l'examen réinitialisé", result));
    }
}
