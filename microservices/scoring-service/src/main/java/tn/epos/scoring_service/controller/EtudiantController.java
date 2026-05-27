package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.EtudiantDTO; // New Import
import tn.epos.scoring_service.entities.Etudiant;
import tn.epos.scoring_service.service.EtudiantService;

import java.util.List;

@RestController
@RequestMapping("/api/etudiants")
public class EtudiantController {

    private static final String NOT_FOUND_MSG = "Étudiant non trouvé";

    @Autowired
    private EtudiantService etudiantService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<EtudiantDTO>>> getAllEtudiants() {
        List<EtudiantDTO> dtos = etudiantService.getAllEtudiants().stream()
                .map(EtudiantDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<EtudiantDTO>> getEtudiantById(@PathVariable Long id) {
        return etudiantService.getEtudiantById(id)
                .map(etudiant -> ResponseEntity.ok(ApiResponse.ok(EtudiantDTO.fromEntity(etudiant))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(NOT_FOUND_MSG)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<EtudiantDTO>> createEtudiant(@RequestBody Etudiant etudiant) {
        EtudiantDTO saved = EtudiantDTO.fromEntity(etudiantService.saveEtudiant(etudiant));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Étudiant créé avec succès", saved));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<EtudiantDTO>> updateEtudiant(@PathVariable Long id, @RequestBody Etudiant etudiant) {
        return etudiantService.getEtudiantById(id)
                .map(existing -> {
                    existing.setNom(etudiant.getNom());
                    existing.setPrenom(etudiant.getPrenom());
                    existing.setNumero_inscription(etudiant.getNumero_inscription());
                    EtudiantDTO updated = EtudiantDTO.fromEntity(etudiantService.saveEtudiant(existing));
                    return ResponseEntity.ok(ApiResponse.ok("Mise à jour réussie", updated));
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(NOT_FOUND_MSG)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteEtudiant(@PathVariable Long id) {
        if (etudiantService.getEtudiantById(id).isPresent()) {
            etudiantService.deleteEtudiant(id);
            return ResponseEntity.ok(ApiResponse.ok("Étudiant supprimé avec succès"));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(NOT_FOUND_MSG));
    }
}