package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.service.NotationService;

import java.util.List;

@RestController
@RequestMapping("/api/notations")
@CrossOrigin("*")
public class NotationController {

    @Autowired
    private NotationService service;

    // GET : Toutes les notations
    @GetMapping
    public List<Notation> getAll() {
        return service.findAll();
    }

    // GET par ID
    @GetMapping("/{id}")
    public ResponseEntity<Notation> getById(@PathVariable Long id) {
        return service.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // GET par assignment
    @GetMapping("/assignment/{assignmentId}")
    public ResponseEntity<Notation> getByAssignment(@PathVariable Long assignmentId) {
        return service.findByAssignment(assignmentId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // POST : Créer une notation
    @PostMapping
    public Notation create(@RequestBody Notation notation) {
        return service.save(notation);
    }

    // PUT : Mettre à jour une notation
    @PutMapping("/{id}")
    public ResponseEntity<Notation> update(@PathVariable Long id, @RequestBody Notation details) {
        try {
            return ResponseEntity.ok(service.update(id, details));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(null);
        }
    }

    // PATCH : Verrouiller une notation
    @PatchMapping("/{id}/verrouiller")
    public ResponseEntity<Notation> lock(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.verrouiller(id));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // DELETE : Supprimer une notation
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok().build();
    }
}