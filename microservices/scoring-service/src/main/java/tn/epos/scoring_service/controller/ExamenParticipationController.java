package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.ParticipationDTO; // New Import
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.service.ExamenParticipationService;

import java.util.List;

@RestController
@RequestMapping("/api/participations")
public class ExamenParticipationController {

    private static final String NOT_FOUND_MSG = "Participation non trouvée";

    @Autowired
    private ExamenParticipationService participationService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<ParticipationDTO>>> getAllParticipations() {
        List<ParticipationDTO> dtos = participationService.getAll().stream()
                .map(ParticipationDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<ParticipationDTO>> getById(@PathVariable Long id) {
        return participationService.getById(id)
                .map(p -> ResponseEntity.ok(ApiResponse.ok(ParticipationDTO.fromEntity(p))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(NOT_FOUND_MSG)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<ParticipationDTO>> create(@RequestBody ExamenParticipation participation) {
        ParticipationDTO saved = ParticipationDTO.fromEntity(participationService.save(participation));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Participation enregistrée", saved));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<ParticipationDTO>> update(@PathVariable Long id, @RequestBody ExamenParticipation participation) {
        return participationService.getById(id)
                .map(existing -> {
                    existing.setEtudiant(participation.getEtudiant());
                    existing.setNote(participation.getNote());
                    ParticipationDTO updated = ParticipationDTO.fromEntity(participationService.save(existing));
                    return ResponseEntity.ok(ApiResponse.ok("Participation mise à jour", updated));
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(NOT_FOUND_MSG)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        if (participationService.getById(id).isPresent()) {
            participationService.delete(id);
            return ResponseEntity.ok(ApiResponse.ok("Participation supprimée"));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(NOT_FOUND_MSG));
    }
}