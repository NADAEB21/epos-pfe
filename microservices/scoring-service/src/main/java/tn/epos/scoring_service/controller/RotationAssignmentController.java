package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tn.epos.scoring_service.entities.RotationAssignment;
import tn.epos.scoring_service.service.RotationAssignmentService;

import java.util.List;

@RestController
@RequestMapping("/api/assignments")
@CrossOrigin("*")
public class RotationAssignmentController {

    @Autowired
    private RotationAssignmentService service;

    // GET : toutes les assignments
    @GetMapping
    public List<RotationAssignment> getAll() {
        return service.findAll();
    }

    // GET : par ID
    @GetMapping("/{id}")
    public ResponseEntity<RotationAssignment> getById(@PathVariable Long id) {
        return service.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // GET : toutes les assignments d'une rotation spécifique
    @GetMapping("/rotation/{rotationId}")
    public List<RotationAssignment> getByRotation(@PathVariable Long rotationId) {
        return service.findByRotation(rotationId);
    }

    // POST : créer une assignment
    @PostMapping
    public RotationAssignment create(@RequestBody RotationAssignment assignment) {
        return service.save(assignment);
    }

    // PATCH : confirmer la présence
    @PatchMapping("/{id}/presence")
    public RotationAssignment updatePresence(@PathVariable Long id, @RequestParam boolean present) {
        return service.confirmerPresence(id, present);
    }

    // PUT : mettre à jour une assignment
    @PutMapping("/{id}")
    public ResponseEntity<RotationAssignment> update(@PathVariable Long id, @RequestBody RotationAssignment details) {
        try {
            return ResponseEntity.ok(service.update(id, details));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // DELETE : supprimer une assignment
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok().build();
    }
}