package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.service.RotationService;

import java.util.List;

@RestController
@RequestMapping("/api/rotations")
@CrossOrigin("*")
public class RotationController {

    @Autowired
    private RotationService rotationService;

    // GET : toutes les rotations
    @GetMapping
    public List<Rotation> getAll() {
        return rotationService.findAll();
    }

    // GET : rotation par ID
    @GetMapping("/{id}")
    public ResponseEntity<Rotation> getById(@PathVariable Long id) {
        return rotationService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // GET : rotations par groupe
    @GetMapping("/group/{groupId}")
    public List<Rotation> getByGroup(@PathVariable Long groupId) {
        return rotationService.findByGroup(groupId);
    }

    // POST : créer une rotation
    @PostMapping
    public Rotation create(@RequestBody Rotation rotation) {
        return rotationService.save(rotation);
    }

    // PUT : modifier une rotation
    @PutMapping("/{id}")
    public ResponseEntity<Rotation> update(@PathVariable Long id, @RequestBody Rotation details) {
        try {
            return ResponseEntity.ok(rotationService.update(id, details));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // DELETE : supprimer une rotation
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        rotationService.delete(id);
        return ResponseEntity.ok().build();
    }
}