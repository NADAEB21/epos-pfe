package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.service.RotationService;

import java.util.List;

@RestController
@RequestMapping("/api/rotations")
public class RotationController {

    @Autowired
    private RotationService rotationService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<Rotation>>> getAll() {
        return ResponseEntity.ok(ApiResponse.ok(rotationService.findAll()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<Rotation>> getById(@PathVariable Long id) {
        return rotationService.findById(id)
                .map(r -> ResponseEntity.ok(ApiResponse.ok(r)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Rotation non trouvée")));
    }

    @GetMapping("/group/{groupId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<Rotation>>> getByGroup(@PathVariable Long groupId) {
        return ResponseEntity.ok(ApiResponse.ok(rotationService.findByGroup(groupId)));
    }

    @GetMapping("/station/{stationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<Rotation>>> getByStation(@PathVariable Long stationId) {
        return ResponseEntity.ok(ApiResponse.ok(rotationService.findByStation(stationId)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Rotation>> create(@RequestBody Rotation rotation) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Rotation créée", rotationService.save(rotation)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Rotation>> update(@PathVariable Long id, @RequestBody Rotation details) {
        return ResponseEntity.ok(ApiResponse.ok("Rotation mise à jour", rotationService.update(id, details)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        rotationService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Rotation supprimée"));
    }
}