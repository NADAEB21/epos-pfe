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
        entity.setEmail(dto.email());
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

    /**
     * Partial update of a directory record.
     *
     * <p><b>Contract: {@code null} means "not supplied", never "erase".</b> A field
     * absent from the body is left untouched. This is deliberate — the blunt
     * "copy every field off the DTO" version silently wiped whatever the caller
     * omitted (#215), and the e-mail column is exactly the kind of field a
     * narrow caller (the convocations quick-fix, the inline roster edit) sends
     * on its own.
     *
     * <p>Clearing a value is still possible and stays explicit: send an
     * <b>empty string</b>. So {@code null} = leave alone, {@code ""} = erase.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<EtudiantDTO>> updateEtudiant(@PathVariable Long id,
            @RequestBody EtudiantDTO dto) {
        return etudiantService.getEtudiantById(id)
                .map(existing -> {
                    if (dto.nom() != null) {
                        existing.setNom(dto.nom());
                    }
                    if (dto.prenom() != null) {
                        existing.setPrenom(dto.prenom());
                    }
                    if (dto.numero_inscription() != null) {
                        existing.setNumero_inscription(dto.numero_inscription());
                    }
                    if (dto.email() != null) {
                        existing.setEmail(dto.email().trim());
                    }
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