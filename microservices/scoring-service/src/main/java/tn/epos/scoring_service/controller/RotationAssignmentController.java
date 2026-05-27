package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.RotationAssignmentDTO; // New Import
import tn.epos.scoring_service.entities.RotationAssignment;
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

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<RotationAssignmentDTO>> create(@RequestBody RotationAssignment assignment) {
        RotationAssignmentDTO saved = RotationAssignmentDTO.fromEntity(service.save(assignment));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Assignment créé avec succès", saved));
    }

    @PatchMapping("/{id}/presence")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<RotationAssignmentDTO>> updatePresence(@PathVariable Long id, @RequestParam boolean present) {
        RotationAssignmentDTO updated = RotationAssignmentDTO.fromEntity(service.confirmerPresence(id, present));
        return ResponseEntity.ok(ApiResponse.ok("Présence mise à jour", updated));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<RotationAssignmentDTO>> update(@PathVariable Long id, @RequestBody RotationAssignment details) {
        RotationAssignmentDTO updated = RotationAssignmentDTO.fromEntity(service.update(id, details));
        return ResponseEntity.ok(ApiResponse.ok("Assignment modifié", updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Assignment supprimé"));
    }
}