package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.RotationAssignmentDTO; // New Import
import tn.epos.scoring_service.service.RotationAssignmentService;

import java.util.List;

@RestController
@RequestMapping("/api/assignments")
public class RotationAssignmentController {

    private static final String NOT_FOUND_MSG = "Assignment non trouvé";

    @Autowired
    private RotationAssignmentService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<RotationAssignmentDTO>>> getAll() {
        List<RotationAssignmentDTO> dtos = service.findAll().stream()
                .map(RotationAssignmentDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<RotationAssignmentDTO>> getById(@PathVariable Long id) {
        return service.findById(id)
                .map(a -> ResponseEntity.ok(ApiResponse.ok(RotationAssignmentDTO.fromEntity(a))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(NOT_FOUND_MSG)));
    }

    @GetMapping("/rotation/{rotationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<RotationAssignmentDTO>>> getByRotation(@PathVariable Long rotationId) {
        List<RotationAssignmentDTO> dtos = service.findByRotation(rotationId).stream()
                .map(RotationAssignmentDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    // =========================================================================
    // POST / PUT / DELETE /api/assignments et PATCH /{id}/presence — SUPPRIMES (#218).
    // Ne pas reintroduire.
    //
    // Un assignment est le PRODUIT de la generation du circuit : de l'etat derive, pas
    // une ressource qu'on redige. Aucune des quatre portes n'avait de garde — mesure en
    // direct, un evaluateur a retourne la presence sur la rotation d'un collegue. Zero
    // appelant dans les deux clients.
    //
    // La presence a son acte, utilise lui, au grain ou le responsable travaille :
    //   PATCH /api/lots/{lotId}/presence  ->  LotAssignmentService.markPresence
    // Voir le bloc de suppression dans RotationAssignmentService pour le detail.
    // =========================================================================
}