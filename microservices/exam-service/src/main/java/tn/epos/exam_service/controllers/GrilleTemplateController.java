package tn.epos.exam_service.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tn.epos.exam_service.dto.request.GrilleTemplateRequest;
import tn.epos.exam_service.dto.response.ApiResponse;
import tn.epos.exam_service.dto.response.ExamenExportResponse;
import tn.epos.exam_service.dto.response.GrilleTemplateResponse;
import tn.epos.exam_service.services.GrilleTemplateService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Templates & Export", description = "BF2.4 Templates de grilles réutilisables — BF2.5 Import/Export")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
public class GrilleTemplateController {

    private final GrilleTemplateService templateService;
    private final ObjectMapper objectMapper;

    // ── BF2.4 : Templates ────────────────────────────────────────────────────

    @PostMapping("/templates/grilles")
    @Operation(summary = "Créer un template de grille manuellement")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<GrilleTemplateResponse>> creer(
            @Valid @RequestBody GrilleTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Template créé", templateService.creer(request)));
    }

    @PostMapping("/grilles/{grilleId}/templates")
    @Operation(summary = "Sauvegarder une grille existante comme template",
            description = "BF2.4 — Crée un template réutilisable à partir d'une grille configurée")
    public ResponseEntity<ApiResponse<GrilleTemplateResponse>> sauvegarderDepuisGrille(
            @PathVariable Long grilleId,
            @RequestParam String nom) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Template sauvegardé",
                        templateService.sauvegarderDepuisGrille(grilleId, nom)));
    }

    @GetMapping("/templates/grilles")
    @Operation(summary = "Lister tous les templates de grilles")
    public ResponseEntity<ApiResponse<List<GrilleTemplateResponse>>> lister() {
        return ResponseEntity.ok(ApiResponse.ok(templateService.listerTous()));
    }

    @GetMapping("/templates/grilles/{id}")
    @Operation(summary = "Récupérer un template par ID")
    public ResponseEntity<ApiResponse<GrilleTemplateResponse>> trouverParId(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(templateService.trouverParId(id)));
    }

    @DeleteMapping("/templates/grilles/{id}")
    @Operation(summary = "Supprimer un template")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> supprimer(@PathVariable Long id) {
        templateService.supprimer(id);
        return ResponseEntity.ok(ApiResponse.ok("Template supprimé", null));
    }

    @PostMapping("/templates/grilles/{templateId}/appliquer/stations/{stationId}")
    @Operation(summary = "Appliquer un template sur une station",
            description = "BF2.4 — Remplace la grille actuelle de la station par le template")
    public ResponseEntity<ApiResponse<Void>> appliquerSurStation(
            @PathVariable Long templateId,
            @PathVariable Long stationId) {
        templateService.appliquerSurStation(templateId, stationId);
        return ResponseEntity.ok(ApiResponse.ok("Template appliqué avec succès", null));
    }

    // ── BF2.4 : Duplication d'examen ─────────────────────────────────────────

    @PostMapping("/examens/{examenId}/dupliquer")
    @Operation(summary = "Dupliquer un examen avec toutes ses stations et grilles",
            description = "BF2.4 — Crée une copie complète au statut BROUILLON")
    public ResponseEntity<ApiResponse<Long>> dupliquerExamen(
            @PathVariable Long examenId,
            @RequestParam String nouveauNom) {
        Long nouvelId = templateService.dupliquerExamen(examenId, nouveauNom);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Examen dupliqué", nouvelId));
    }

    // ── BF2.5 : Export JSON ───────────────────────────────────────────────────

    @GetMapping("/examens/{examenId}/export")
    @Operation(summary = "Exporter la configuration complète d'un examen en JSON",
            description = "BF2.5 — Inclut toutes les stations et grilles")
    public ResponseEntity<byte[]> exporterExamen(@PathVariable Long examenId) throws Exception {
        ExamenExportResponse export = templateService.exporterExamen(examenId);
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(export);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"examen_" + examenId + "_export.json\"")
                .body(json);
    }

    // ── BF2.5 : Import JSON ───────────────────────────────────────────────────

    @PostMapping(value = "/stations/{stationId}/grille/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Importer une grille depuis un fichier JSON",
            description = "BF2.5 — Remplace la grille actuelle de la station")
    public ResponseEntity<ApiResponse<Void>> importerGrilleJson(
            @PathVariable Long stationId,
            @RequestParam("fichier") MultipartFile fichier) throws IOException {

        if (fichier.isEmpty()) {
            throw new tn.epos.exam_service.exception.BusinessException("Le fichier est vide");
        }
        String json = new String(fichier.getBytes(), StandardCharsets.UTF_8);
        templateService.importerGrilleJson(stationId, json);
        return ResponseEntity.ok(ApiResponse.ok("Grille importée avec succès", null));
    }
}
