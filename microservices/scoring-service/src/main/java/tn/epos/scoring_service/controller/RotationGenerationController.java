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
import tn.epos.scoring_service.service.RotationGenerationService;

/**
 * Per-lot rotation generation endpoint (Phase 2 — exam day).
 *
 * <p>Mounted under {@code /api/rotations} so it routes to scoring-service at the
 * gateway ({@code /api/v1/rotations/**}). It is deliberately NOT under
 * {@code /api/examens/**}, which the gateway routes to exam-service.
 *
 * <p>Generation is now scoped to a single lot and gated to exam day: the exam
 * must be EN_COURS and the lot's presence must already be marked. Matière scope is
 * still enforced cross-service — generation reads the exam via the JWT-forwarded
 * exam-service call, which rejects exams outside the caller's matière.
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
}
