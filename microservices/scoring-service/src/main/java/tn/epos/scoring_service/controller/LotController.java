package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.service.LotService;

import java.util.List;

@RestController
@RequestMapping("/api/lots")
@CrossOrigin("*")
public class LotController {

    @Autowired
    private LotService lotService;

    // GET : tous les lots
    @GetMapping
    public List<Lot> getAllLots() {
        return lotService.findAll();
    }

    // GET : lot par ID
    @GetMapping("/{id}")
    public ResponseEntity<Lot> getLotById(@PathVariable Long id) {
        return lotService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // POST : créer un lot
    @PostMapping
    public Lot createLot(@RequestBody Lot lot) {
        return lotService.save(lot);
    }

    // PUT : modifier un lot
    @PutMapping("/{id}")
    public ResponseEntity<Lot> updateLot(@PathVariable Long id, @RequestBody Lot lotDetails) {
        try {
            return ResponseEntity.ok(lotService.update(id, lotDetails));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // DELETE : supprimer un lot
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLot(@PathVariable Long id) {
        lotService.delete(id);
        return ResponseEntity.ok().build();
    }
}