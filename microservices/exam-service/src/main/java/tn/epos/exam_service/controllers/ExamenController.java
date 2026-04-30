package tn.epos.exam_service.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import tn.epos.exam_service.dto.request.ExamenRequest;
import tn.epos.exam_service.dto.response.ApiResponse;
import tn.epos.exam_service.dto.response.ExamenResponse;
import tn.epos.exam_service.enums.StatutExamen;
import tn.epos.exam_service.services.ExamenService;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.MalformedURLException;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/api/examens")
@RequiredArgsConstructor
@Tag(name = "Examens", description = "Gestion des examens EPOS")
public class ExamenController {
    private final ExamenService examenService;

    @PostMapping
    @Operation(summary = "Créer un examen", description = "Statut initial : BROUILLON")
    public ResponseEntity<ApiResponse<ExamenResponse>> creer(
            @Valid @RequestBody ExamenRequest request) {

        ExamenResponse response = examenService.creer(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Examen créé avec succès", response));
    }

    @GetMapping
    @Operation(summary = "Lister les examens", description = "Filtrage optionnel par statut")
    public ResponseEntity<ApiResponse<List<ExamenResponse>>> lister(
            @Parameter(description = "Filtrer par statut (optionnel)")
            @RequestParam(required = false) StatutExamen statut) {

        List<ExamenResponse> examens = (statut != null)
                ? examenService.listerParStatut(statut)
                : examenService.listerTous();

        return ResponseEntity.ok(ApiResponse.success(examens));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Détail d'un examen", description = "Inclut la liste des stations")
    public ResponseEntity<ApiResponse<ExamenResponse>> trouverParId(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(examenService.trouverParId(id)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Modifier un examen", description = "Uniquement si statut BROUILLON")
    public ResponseEntity<ApiResponse<ExamenResponse>> modifier(
            @PathVariable Long id,
            @Valid @RequestBody ExamenRequest request) {

        return ResponseEntity.ok(
                ApiResponse.success("Examen modifié avec succès", examenService.modifier(id, request))
        );
    }

    @PatchMapping("/{id}/statut")
    @Operation(summary = "Changer le statut d'un examen",
            description = "Transitions valides : BROUILLON→CONFIGURE→EN_COURS→TERMINE→ARCHIVE")
    public ResponseEntity<ApiResponse<ExamenResponse>> changerStatut(
            @PathVariable Long id,
            @RequestParam StatutExamen statut) {

        return ResponseEntity.ok(
                ApiResponse.success("Statut mis à jour", examenService.changerStatut(id, statut))
        );
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Supprimer un examen", description = "Uniquement si statut BROUILLON ou CONFIGURE")
    public ResponseEntity<ApiResponse<Void>> supprimer(@PathVariable Long id) {
        examenService.supprimer(id);
        return ResponseEntity.ok(ApiResponse.success("Examen supprimé avec succès", null));
    }

    // pdf
    @PostMapping(value = "/{id}/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Importer le PDF du sujet d'examen", description = "Max 10 MB, format PDF uniquement")
    public ResponseEntity<ApiResponse<ExamenResponse>> importerPdf(
            @PathVariable Long id,
            @RequestParam("fichier") MultipartFile fichier) {

        return ResponseEntity.ok(
                ApiResponse.success("PDF importé avec succès", examenService.importerPdf(id, fichier))
        );
    }

    @GetMapping("/{id}/pdf")
    @Operation(summary = "Télécharger le PDF du sujet d'examen")
    public ResponseEntity<Resource> telechargerPdf(@PathVariable Long id) {
        String chemin = examenService.obtenirCheminPdf(id);
        try {
            Resource resource = new UrlResource(Paths.get(chemin).toUri());
            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"" + resource.getFilename() + "\"")
                    .body(resource);
        } catch (MalformedURLException e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
