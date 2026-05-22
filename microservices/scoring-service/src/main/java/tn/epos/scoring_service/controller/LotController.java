package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.service.LotService;

import java.util.List;

@RestController
@RequestMapping("/api/lots")
public class LotController {

    @Autowired
    private LotService lotService;

    // GET : tous les lots
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public List<Lot> getAllLots() {
        return lotService.findAll();
    }

    // GET : lot par ID
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<Lot> getLotById(@PathVariable Long id) {
        return lotService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // POST : créer un lot
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public Lot createLot(@RequestBody Lot lot) {
        return lotService.save(lot);
    }

    // PUT : modifier un lot
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<Lot> updateLot(@PathVariable Long id, @RequestBody Lot lotDetails) {
        return ResponseEntity.ok(lotService.update(id, lotDetails));
    }

    // DELETE : supprimer un lot
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<Void> deleteLot(@PathVariable Long id) {
        lotService.delete(id);
        return ResponseEntity.noContent().build();
    }
}