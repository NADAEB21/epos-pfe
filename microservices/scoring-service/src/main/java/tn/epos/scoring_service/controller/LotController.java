package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.LotDTO; // New Import
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.service.LotService;

import java.util.List;

@RestController
@RequestMapping("/api/lots")
public class LotController {

    private static final String NOT_FOUND_MSG = "Lot non trouvé";

    @Autowired
    private LotService lotService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<LotDTO>>> getAllLots() {
        List<LotDTO> dtos = lotService.findAll().stream()
                .map(LotDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<LotDTO>> getLotById(@PathVariable Long id) {
        return lotService.findById(id)
                .map(lot -> ResponseEntity.ok(ApiResponse.ok(LotDTO.fromEntity(lot))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(NOT_FOUND_MSG)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<LotDTO>> createLot(@RequestBody LotDTO dto) {
        Lot entity = new Lot();
        entity.setNumeroLot(dto.numeroLot());
        entity.setTailleLot(dto.tailleLot());
        entity.setStatut(dto.statut());
        entity.setEvaluateurId(dto.evaluateurId());
        entity.setExamenId(dto.examenId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Lot créé avec succès", LotDTO.fromEntity(lotService.save(entity))));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<LotDTO>> updateLot(@PathVariable Long id, @RequestBody LotDTO dto) {
        Lot entity = new Lot();
        entity.setNumeroLot(dto.numeroLot());
        entity.setTailleLot(dto.tailleLot());
        entity.setStatut(dto.statut());
        // Pass to service update logic
        return ResponseEntity.ok(ApiResponse.ok("Lot mis à jour", LotDTO.fromEntity(lotService.update(id, entity))));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Void>> deleteLot(@PathVariable Long id) {
        lotService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Lot supprimé"));
    }
}