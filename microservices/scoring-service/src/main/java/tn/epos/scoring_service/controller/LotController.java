package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.service.LotService;

import java.util.List;

@RestController
@RequestMapping("/api/lots")
public class LotController {

    @Autowired
    private LotService lotService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<Lot>>> getAllLots() {
        return ResponseEntity.ok(ApiResponse.ok(lotService.findAll()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<Lot>> getLotById(@PathVariable Long id) {
        return lotService.findById(id)
                .map(lot -> ResponseEntity.ok(ApiResponse.ok(lot)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Lot non trouvé")));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Lot>> createLot(@RequestBody Lot lot) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Lot créé avec succès", lotService.save(lot)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Lot>> updateLot(@PathVariable Long id, @RequestBody Lot lotDetails) {
        return ResponseEntity.ok(ApiResponse.ok("Lot mis à jour", lotService.update(id, lotDetails)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Void>> deleteLot(@PathVariable Long id) {
        lotService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Lot supprimé"));
    }
}