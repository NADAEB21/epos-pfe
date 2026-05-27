package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse; // Added import
import tn.epos.scoring_service.entities.Etudiant;
import tn.epos.scoring_service.service.EtudiantService;

import java.util.List;

@RestController
@RequestMapping("/api/etudiants")
public class EtudiantController {

    @Autowired
    private EtudiantService etudiantService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<Etudiant>>> getAllEtudiants() {
        return ResponseEntity.ok(ApiResponse.ok(etudiantService.getAllEtudiants()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<Etudiant>> getEtudiantById(@PathVariable Long id) {
        return etudiantService.getEtudiantById(id)
                .map(etudiant -> ResponseEntity.ok(ApiResponse.ok(etudiant)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Étudiant non trouvé")));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Etudiant>> createEtudiant(@RequestBody Etudiant etudiant) {
        Etudiant saved = etudiantService.saveEtudiant(etudiant);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Étudiant créé avec succès", saved));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Etudiant>> updateEtudiant(@PathVariable Long id, @RequestBody Etudiant etudiant) {
        return etudiantService.getEtudiantById(id)
                .map(existing -> {
                    existing.setNom(etudiant.getNom());
                    existing.setPrenom(etudiant.getPrenom());
                    existing.setNumero_inscription(etudiant.getNumero_inscription());
                    return ResponseEntity.ok(ApiResponse.ok("Mise à jour réussie", etudiantService.saveEtudiant(existing)));
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Étudiant non trouvé")));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteEtudiant(@PathVariable Long id) {
        if (etudiantService.getEtudiantById(id).isPresent()) {
            etudiantService.deleteEtudiant(id);
            return ResponseEntity.ok(ApiResponse.ok("Étudiant supprimé avec succès"));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Étudiant non trouvé"));
    }
}