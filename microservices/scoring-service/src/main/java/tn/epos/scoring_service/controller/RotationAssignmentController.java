package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.scoring_service.entities.RotationAssignment;
import tn.epos.scoring_service.service.RotationAssignmentService;

import java.util.List;

@RestController
@RequestMapping("/api/assignments")
public class RotationAssignmentController {

    @Autowired
    private RotationAssignmentService service;

    // GET : toutes les assignments
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public List<RotationAssignment> getAll() {
        return service.findAll();
    }

    // GET : par ID
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<RotationAssignment> getById(@PathVariable Long id) {
        return service.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // GET : toutes les assignments d'une rotation spécifique
    @GetMapping("/rotation/{rotationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public List<RotationAssignment> getByRotation(@PathVariable Long rotationId) {
        return service.findByRotation(rotationId);
    }

    // POST : créer une assignment
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public RotationAssignment create(@RequestBody RotationAssignment assignment) {
        return service.save(assignment);
    }

    // PATCH : confirmer la présence
    @PatchMapping("/{id}/presence")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<RotationAssignment> updatePresence(@PathVariable Long id, @RequestParam boolean present) {
        return ResponseEntity.ok(service.confirmerPresence(id, present));
    }

    // PUT : mettre à jour une assignment
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<RotationAssignment> update(@PathVariable Long id, @RequestBody RotationAssignment details) {
        return ResponseEntity.ok(service.update(id, details));
    }

    // DELETE : supprimer une assignment
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}