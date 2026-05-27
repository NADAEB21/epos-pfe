package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.service.NotationService;

import java.util.List;

@RestController
@RequestMapping("/api/notations")
public class NotationController {

    @Autowired
    private NotationService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<Notation>>> getAll() {
        return ResponseEntity.ok(ApiResponse.ok(service.findAll()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<Notation>> getById(@PathVariable Long id) {
        return service.findById(id)
                .map(n -> ResponseEntity.ok(ApiResponse.ok(n)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Notation non trouvée")));
    }

    @GetMapping("/assignment/{assignmentId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<Notation>> getByAssignment(@PathVariable Long assignmentId) {
        return service.findByAssignment(assignmentId)
                .map(n -> ResponseEntity.ok(ApiResponse.ok(n)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Aucune notation pour cet assignment")));
    }

    @GetMapping("/station/{stationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<Notation>>> getByStation(@PathVariable Long stationId) {
        return ResponseEntity.ok(ApiResponse.ok(service.findByStation(stationId)));
    }

    @GetMapping("/grille/{grilleId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<Notation>>> getByGrille(@PathVariable Long grilleId) {
        return ResponseEntity.ok(ApiResponse.ok(service.findByGrille(grilleId)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'EVALUATEUR', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Notation>> create(@RequestBody Notation notation) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Évaluation initialisée", service.save(notation)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'EVALUATEUR', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Notation>> update(@PathVariable Long id, @RequestBody Notation details) {
        return ResponseEntity.ok(ApiResponse.ok("Notation mise à jour", service.update(id, details)));
    }

    @PatchMapping("/{id}/verrouiller")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<Notation>> lock(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok("Notation verrouillée définitivement", service.verrouiller(id)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Notation supprimée"));
    }
}