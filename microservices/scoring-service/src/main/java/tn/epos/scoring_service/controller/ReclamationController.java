package tn.epos.scoring_service.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.ReclamationDTO;
import tn.epos.scoring_service.dto.ReclamationRequest;
import tn.epos.scoring_service.dto.ReclamationResolveRequest;
import tn.epos.scoring_service.service.ReclamationService;

import java.util.List;

/**
 * Student complaint register (#136). RESPONSABLE_MATIERE + SUPER_ADMIN only —
 * NOT the évaluateur (complaints are the responsable's oversight domain, same as
 * the réajustement channel they resolve into). The étudiant has no login, so the
 * responsable files on their behalf.
 *
 * <p>Mounted under {@code /api/reclamations}; the gateway routes
 * {@code /api/v1/reclamations/**} here (RewritePath strips {@code /v1}).
 */
@RestController
@RequestMapping("/api/reclamations")
@RequiredArgsConstructor
public class ReclamationController {

    private final ReclamationService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<ReclamationDTO>> creer(
            @Valid @RequestBody ReclamationRequest request) {
        ReclamationDTO saved = service.creer(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Réclamation enregistrée", saved));
    }

    @GetMapping("/examen/{examenId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<List<ReclamationDTO>>> listerParExamen(
            @PathVariable Long examenId) {
        return ResponseEntity.ok(ApiResponse.ok(service.listerParExamen(examenId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<ReclamationDTO>> trouver(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(service.trouver(id)));
    }

    @PatchMapping("/{id}/resoudre")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<ReclamationDTO>> resoudre(
            @PathVariable Long id, @Valid @RequestBody ReclamationResolveRequest request) {
        ReclamationDTO resolved = service.resoudre(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Réclamation traitée", resolved));
    }
}
