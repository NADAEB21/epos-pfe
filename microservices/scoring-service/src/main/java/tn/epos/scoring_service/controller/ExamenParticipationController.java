package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse; // Added import
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.service.ExamenParticipationService;

import java.util.List;

@RestController
@RequestMapping("/api/participations")
public class ExamenParticipationController {

    @Autowired
    private ExamenParticipationService participationService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<ExamenParticipation>>> getAllParticipations() {
        return ResponseEntity.ok(ApiResponse.ok(participationService.getAll()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<ExamenParticipation>> getById(@PathVariable Long id) {
        return participationService.getById(id)
                .map(p -> ResponseEntity.ok(ApiResponse.ok(p)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Participation non trouvée")));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<ExamenParticipation>> create(@RequestBody ExamenParticipation participation) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Participation enregistrée", participationService.save(participation)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<ExamenParticipation>> update(@PathVariable Long id, @RequestBody ExamenParticipation participation) {
        return participationService.getById(id)
                .map(existing -> {
                    existing.setEtudiant(participation.getEtudiant());
                    existing.setNote(participation.getNote());
                    return ResponseEntity.ok(ApiResponse.ok("Participation mise à jour", participationService.save(existing)));
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Participation non trouvée")));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        if (participationService.getById(id).isPresent()) {
            participationService.delete(id);
            return ResponseEntity.ok(ApiResponse.ok("Participation supprimée"));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Participation non trouvée"));
    }
}