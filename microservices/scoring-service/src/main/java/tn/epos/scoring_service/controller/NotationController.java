package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.service.NotationService;

import java.util.List;

@RestController
@RequestMapping("/api/notations")
public class NotationController {

    @Autowired
    private NotationService service;

    // GET : Toutes les notations
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public List<Notation> getAll() {
        return service.findAll();
    }

    // GET par ID
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<Notation> getById(@PathVariable Long id) {
        return service.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // GET par assignment
    @GetMapping("/assignment/{assignmentId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<Notation> getByAssignment(@PathVariable Long assignmentId) {
        return service.findByAssignment(assignmentId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // GET : notations par station (cross-service)
    @GetMapping("/station/{stationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public List<Notation> getByStation(@PathVariable Long stationId) {
        return service.findByStation(stationId);
    }

    // GET : notations par grille (cross-service)
    @GetMapping("/grille/{grilleId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public List<Notation> getByGrille(@PathVariable Long grilleId) {
        return service.findByGrille(grilleId);
    }

    // POST : Créer une notation
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'EVALUATEUR', 'RESPONSABLE_MATIERE')")
    public Notation create(@RequestBody Notation notation) {
        return service.save(notation);
    }

    // PUT : Mettre à jour une notation
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'EVALUATEUR', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<Notation> update(@PathVariable Long id, @RequestBody Notation details) {
        return ResponseEntity.ok(service.update(id, details));
    }

    // PATCH : Verrouiller une notation
    @PatchMapping("/{id}/verrouiller")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'EVALUATEUR')")
    public ResponseEntity<Notation> lock(@PathVariable Long id) {
        return ResponseEntity.ok(service.verrouiller(id));
    }

    // DELETE : Supprimer une notation
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}