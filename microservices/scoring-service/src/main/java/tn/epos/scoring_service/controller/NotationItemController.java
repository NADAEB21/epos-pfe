package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.NotationItemDTO; // New Import
import tn.epos.scoring_service.entities.NotationItem;
import tn.epos.scoring_service.service.NotationItemService;

import java.util.List;

@RestController
@RequestMapping("/api/notation-items")
public class NotationItemController {

    private static final String NOT_FOUND_MSG = "Critère noté non trouvé";

    @Autowired
    private NotationItemService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<NotationItemDTO>>> getAll() {
        List<NotationItemDTO> dtos = service.findAll().stream()
                .map(NotationItemDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @GetMapping("/notation/{notationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<NotationItemDTO>>> getByNotation(@PathVariable Long notationId) {
        List<NotationItemDTO> dtos = service.findByNotation(notationId).stream()
                .map(NotationItemDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<NotationItemDTO>> getById(@PathVariable Long id) {
        return service.findById(id)
                .map(item -> ResponseEntity.ok(ApiResponse.ok(NotationItemDTO.fromEntity(item))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(NOT_FOUND_MSG)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<NotationItemDTO>> create(@RequestBody NotationItemDTO dto) {
        NotationItem entity = new NotationItem();
        entity.setItemId(dto.itemId());
        entity.setValeur(dto.valeur());
        entity.setCommentaire(dto.commentaire());

        NotationItemDTO saved = NotationItemDTO.fromEntity(service.save(entity));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Note enregistrée", saved));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<NotationItemDTO>> update(@PathVariable Long id,
            @RequestBody NotationItemDTO dto) {
        NotationItem updateData = new NotationItem();
        updateData.setValeur(dto.valeur());
        updateData.setCommentaire(dto.commentaire());

        NotationItemDTO updated = NotationItemDTO.fromEntity(service.update(id, updateData));
        return ResponseEntity.ok(ApiResponse.ok("Note modifiée", updated));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Note supprimée"));
    }
}