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
 * Rotation auto-generation endpoint (Phase C — Option B).
 *
 * <p>Mounted under {@code /api/rotations} so it routes to scoring-service at the
 * gateway ({@code /api/v1/rotations/**}). It is deliberately NOT under
 * {@code /api/examens/**}, which the gateway routes to exam-service.
 *
 * <p>Matière scope is enforced cross-service: generation reads the exam via the
 * JWT-forwarded exam-service call, which rejects exams outside the caller's
 * matière — a responsable cannot generate for an exam they cannot read.
 */
@RestController
@RequestMapping("/api/rotations")
@RequiredArgsConstructor
public class RotationGenerationController {

    private final RotationGenerationService generationService;

    @PostMapping("/examens/{examenId}/generer")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<GenerationResult>> generer(@PathVariable Long examenId) {
        GenerationResult result = generationService.generate(examenId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Rotations générées avec succès", result));
    }
}
