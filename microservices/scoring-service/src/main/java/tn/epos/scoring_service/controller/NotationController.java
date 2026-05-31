package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.NotationDTO; // New Import
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.service.NotationService;

import java.util.List;

@RestController
@RequestMapping("/api/notations")
public class NotationController {

    private static final String NOT_FOUND_MSG = "Notation non trouvée";

    @Autowired
    private NotationService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<NotationDTO>>> getAll() {
        List<NotationDTO> dtos = service.findAll().stream()
                .map(NotationDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<NotationDTO>> getById(@PathVariable Long id) {
        return service.findById(id)
                .map(n -> ResponseEntity.ok(ApiResponse.ok(NotationDTO.fromEntity(n))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(NOT_FOUND_MSG)));
    }

    @GetMapping("/assignment/{assignmentId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<NotationDTO>> getByAssignment(@PathVariable Long assignmentId) {
        return service.findByAssignment(assignmentId)
                .map(n -> ResponseEntity.ok(ApiResponse.ok(NotationDTO.fromEntity(n))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("Aucune notation pour cet assignment")));
    }

    @GetMapping("/station/{stationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<NotationDTO>>> getByStation(@PathVariable Long stationId) {
        List<NotationDTO> dtos = service.findByStation(stationId).stream()
                .map(NotationDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @GetMapping("/grille/{grilleId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<NotationDTO>>> getByGrille(@PathVariable Long grilleId) {
        List<NotationDTO> dtos = service.findByGrille(grilleId).stream()
                .map(NotationDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'EVALUATEUR', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<NotationDTO>> create(@RequestBody NotationDTO dto) {
        Notation entity = new Notation();
        entity.setScore_final(dto.score_final());
        entity.setTemps_additionnel(dto.temps_additionnel());
        entity.setStationId(dto.stationId());
        entity.setGrilleId(dto.grilleId());
        entity.setVerouillee(false);

        NotationDTO saved = NotationDTO.fromEntity(service.save(entity, dto.assignmentId()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Évaluation initialisée", saved));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'EVALUATEUR', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<NotationDTO>> update(@PathVariable Long id, @RequestBody NotationDTO dto) {
        Notation updateData = new Notation();
        updateData.setScore_final(dto.score_final());
        updateData.setIs_synced(dto.is_synced());
        updateData.setTemps_additionnel(dto.temps_additionnel());

        NotationDTO updated = NotationDTO.fromEntity(service.update(id, updateData));
        return ResponseEntity.ok(ApiResponse.ok("Notation mise à jour", updated));
    }

    @PatchMapping("/{id}/verrouiller")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<NotationDTO>> lock(@PathVariable Long id) {
        NotationDTO locked = NotationDTO.fromEntity(service.verrouiller(id));
        return ResponseEntity.ok(ApiResponse.ok("Notation verrouillée définitivement", locked));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Notation supprimée"));
    }
}