package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.scoring_service.entities.NotationItem;
import tn.epos.scoring_service.service.NotationItemService;

import java.util.List;

@RestController
@RequestMapping("/api/notation-items")
public class NotationItemController {

    @Autowired
    private NotationItemService service;  // Ici on utilise le service, pas l'entité

    // GET : tous les NotationItems
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public List<NotationItem> getAll() {
        return service.findAll();
    }

    // GET : tous les NotationItems d'une notation précise
    @GetMapping("/notation/{notationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public List<NotationItem> getByNotation(@PathVariable Long notationId) {
        return service.findByNotation(notationId);
    }

    // GET par ID
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<NotationItem> getById(@PathVariable Long id) {
        return service.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // POST : créer un NotationItem
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public NotationItem create(@RequestBody NotationItem item) {
        return service.save(item);
    }

    // PUT : mettre à jour un NotationItem
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<NotationItem> update(@PathVariable Long id, @RequestBody NotationItem details) {
        try {
            return ResponseEntity.ok(service.update(id, details));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // DELETE : supprimer un NotationItem
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}