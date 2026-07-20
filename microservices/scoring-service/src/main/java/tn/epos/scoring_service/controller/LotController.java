package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.LotDTO; // New Import
import tn.epos.scoring_service.dto.ParticipationDTO;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.service.LotAssignmentService;
import tn.epos.scoring_service.service.LotService;

import java.util.List;

@RestController
@RequestMapping("/api/lots")
public class LotController {

    private static final String NOT_FOUND_MSG = "Lot non trouvé";

    @Autowired
    private LotService lotService;

    @Autowired
    private LotAssignmentService lotAssignmentService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<LotDTO>>> getAllLots(
            @RequestParam(required = false) Long examenId) {
        List<Lot> lots = (examenId != null)
                ? lotService.findByExamenId(examenId)
                : lotService.findAll();
        List<LotDTO> dtos = lots.stream()
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
        entity.setJour(dto.jour()); // #147 — null = jour unique de l'examen
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
        entity.setJour(dto.jour()); // #147 — appliqué seulement si non-null (cf. LotService.update)
        // Pass to service update logic
        return ResponseEntity.ok(ApiResponse.ok("Lot mis à jour", LotDTO.fromEntity(lotService.update(id, entity))));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Void>> deleteLot(@PathVariable Long id) {
        lotService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Lot supprimé"));
    }

    /**
     * Manually move one enrolled student into this lot (#165) — RESP/ADMIN,
     * CONFIGURE-only. Reads as "put participation {participationId} into lot
     * {targetLotId}", the single-student alternative to the wipe-and-redo
     * {@code POST /examens/{id}/repartir}. Returns the updated participation
     * (now carrying the new lotId). 404 unknown lot/participation; 400 when the
     * target lot is in another exam or the exam is no longer CONFIGURE.
     */
    @PatchMapping("/{targetLotId}/etudiants/{participationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<ParticipationDTO>> deplacerEtudiant(
            @PathVariable Long targetLotId, @PathVariable Long participationId) {
        ParticipationDTO dto = lotAssignmentService.deplacerEtudiant(targetLotId, participationId);
        return ResponseEntity.ok(ApiResponse.ok("Étudiant déplacé", dto));
    }
}