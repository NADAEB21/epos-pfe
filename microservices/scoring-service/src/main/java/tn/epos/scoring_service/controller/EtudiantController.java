package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.EtudiantDTO; // New Import
import tn.epos.scoring_service.dto.ImportEtudiantRequest;
import tn.epos.scoring_service.dto.ImportResult;
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
    // FIX: Change parameter from Etudiant to EtudiantDTO
    public ResponseEntity<ApiResponse<EtudiantDTO>> createEtudiant(@RequestBody EtudiantDTO dto) {
        // Convert DTO to Entity for the service
        Etudiant entity = new Etudiant();
        entity.setNom(dto.nom());
        entity.setPrenom(dto.prenom());
        entity.setNumero_inscription(dto.numero_inscription());

        EtudiantDTO saved = EtudiantDTO.fromEntity(etudiantService.saveEtudiant(entity));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Étudiant créé avec succès", saved));
    }

    /**
     * Bulk import + enrol (gap #11). The frontend parses CSV/.xlsx (SheetJS) into
     * normalized rows and posts them as JSON (NOT multipart). For each row we
     * find-or-create the student by numero_inscription, then enrol on examenId,
     * skipping any already-enrolled. Returns a per-row outcome so the UI can show
     * a line-by-line result table. RESPONSABLE_MATIERE-allowed, like POST /etudiants.
     */
    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<ImportResult>> importEtudiants(
            @RequestParam Long examenId,
            @RequestBody List<ImportEtudiantRequest> rows) {
        ImportResult result = etudiantService.importStudents(examenId, rows);
        return ResponseEntity.ok(ApiResponse.ok("Import terminé", result));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    // FIX: Change parameter from Etudiant to EtudiantDTO
    public ResponseEntity<ApiResponse<EtudiantDTO>> updateEtudiant(@PathVariable Long id,
            @RequestBody EtudiantDTO dto) {
        return etudiantService.getEtudiantById(id)
                .map(existing -> {
                    // Update only the fields we allow
                    existing.setNom(dto.nom());
                    existing.setPrenom(dto.prenom());
                    existing.setNumero_inscription(dto.numero_inscription());

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