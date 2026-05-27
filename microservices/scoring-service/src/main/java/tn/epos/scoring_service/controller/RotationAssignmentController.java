package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.entities.RotationAssignment;
import tn.epos.scoring_service.service.RotationAssignmentService;

import java.util.List;

@RestController
@RequestMapping("/api/assignments")
public class RotationAssignmentController {

    @Autowired
    private RotationAssignmentService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<RotationAssignment>>> getAll() {
        return ResponseEntity.ok(ApiResponse.ok(service.findAll()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<RotationAssignment>> getById(@PathVariable Long id) {
        return service.findById(id)
                .map(a -> ResponseEntity.ok(ApiResponse.ok(a)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Assignment non trouvé")));
    }

    @GetMapping("/rotation/{rotationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<RotationAssignment>>> getByRotation(@PathVariable Long rotationId) {
        return ResponseEntity.ok(ApiResponse.ok(service.findByRotation(rotationId)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<RotationAssignment>> create(@RequestBody RotationAssignment assignment) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Assignment créé avec succès", service.save(assignment)));
    }

    @PatchMapping("/{id}/presence")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<RotationAssignment>> updatePresence(@PathVariable Long id, @RequestParam boolean present) {
        return ResponseEntity.ok(ApiResponse.ok("Présence mise à jour", service.confirmerPresence(id, present)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<RotationAssignment>> update(@PathVariable Long id, @RequestBody RotationAssignment details) {
        return ResponseEntity.ok(ApiResponse.ok("Assignment modifié", service.update(id, details)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Assignment supprimé"));
    }
}