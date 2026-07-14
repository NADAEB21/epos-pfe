package tn.epos.exam_service.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import tn.epos.exam_service.dto.response.PageResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import tn.epos.exam_service.dto.request.ExamenRequest;
import tn.epos.common.dto.ApiResponse;
import tn.epos.exam_service.dto.response.ExamTimingResponse;
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

@RestController
@RequestMapping("/api/examens")
@RequiredArgsConstructor
@Tag(name = "Examens", description = "Gestion des examens EPOS")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
public class ExamenController {
    private final ExamenService examenService;

    @PostMapping
    @Operation(summary = "Créer un examen", description = "Statut initial : BROUILLON")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_RESPONSABLE_MATIERE:' + #request.matiereId)")
    public ResponseEntity<ApiResponse<ExamenResponse>> creer(
            @Valid @RequestBody ExamenRequest request) {

        ExamenResponse response = examenService.creer(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Examen créé avec succès", response));
    }

    @GetMapping
    @Operation(summary = "Lister les examens", description = "Filtrage optionnel par statut, avec pagination")
    public ResponseEntity<ApiResponse<PageResponse<ExamenResponse>>> lister(
            @Parameter(description = "Filtrer par statut (optionnel)")
            @RequestParam(required = false) StatutExamen statut,
            @PageableDefault(size = 20, sort = "dateExamen", direction = Sort.Direction.DESC)
            Pageable pageable) {

        Page<ExamenResponse> examens = (statut != null)
                ? examenService.listerParStatut(statut, pageable)
                : examenService.listerTous(pageable);

        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(examens)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Détail d'un examen", description = "Inclut la liste des stations")
    public ResponseEntity<ApiResponse<ExamenResponse>> trouverParId(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(examenService.trouverParId(id)));
    }

    /**
     * État d'exécution seul (statut / pause / durée) — le SEUL endpoint examen ouvert à
     * l'ÉVALUATEUR. Le {@code @PreAuthorize} au niveau méthode surcharge celui de la classe
     * (SUPER_ADMIN | RESPONSABLE_MATIERE), qui renvoyait 403 à tout évaluateur.
     *
     * <p>Sans lui, scoring-service appelait {@code GET /api/examens/{id}} avec le jeton de
     * l'évaluateur, recevait 403, l'avalait dans un repli « neutre » (statut = null), et le
     * dashboard évaluateur se retrouvait VIDE le jour de l'examen. On expose l'état
     * opérationnel, et rien d'autre : ni stations, ni sujet, ni étudiants.
     */
    @GetMapping("/{id}/timing")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    @Operation(summary = "État d'exécution d'un examen",
               description = "Statut / pause / durée. Lisible par l'évaluateur (dashboard mobile).")
    public ResponseEntity<ApiResponse<ExamTimingResponse>> timing(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(examenService.getTiming(id)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Modifier un examen", description = "Uniquement si statut BROUILLON")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN') or hasAuthority('ROLE_RESPONSABLE_MATIERE:' + #request.matiereId)")
    public ResponseEntity<ApiResponse<ExamenResponse>> modifier(
            @PathVariable Long id,
            @Valid @RequestBody ExamenRequest request) {

        return ResponseEntity.ok(
                ApiResponse.ok("Examen modifié avec succès", examenService.modifier(id, request))
        );
    }

    @PatchMapping("/{id}/statut")
    @Operation(summary = "Changer le statut d'un examen",
            description = "Transitions valides : BROUILLON→CONFIGURE→EN_COURS→TERMINE→ARCHIVE")
    public ResponseEntity<ApiResponse<ExamenResponse>> changerStatut(
            @PathVariable Long id,
            @RequestParam StatutExamen statut) {

        return ResponseEntity.ok(
                ApiResponse.ok("Statut mis à jour", examenService.changerStatut(id, statut))
        );
    }

    @PatchMapping("/{id}/pause")
    @Operation(summary = "Mettre un examen en pause",
            description = "ADR-0009 : pause orthogonale ; l'examen reste EN_COURS. "
                    + "Couvre pauses, coupures repas et examens multi-jours.")
    public ResponseEntity<ApiResponse<ExamenResponse>> mettreEnPause(@PathVariable Long id) {
        return ResponseEntity.ok(
                ApiResponse.ok("Examen mis en pause", examenService.mettreEnPause(id))
        );
    }

    @PatchMapping("/{id}/reprendre")
    @Operation(summary = "Reprendre un examen en pause",
            description = "Cumule la durée de pause écoulée (temps effectif).")
    public ResponseEntity<ApiResponse<ExamenResponse>> reprendre(@PathVariable Long id) {
        return ResponseEntity.ok(
                ApiResponse.ok("Examen repris", examenService.reprendre(id))
        );
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Supprimer un examen", description = "Uniquement si statut BROUILLON ou CONFIGURE")
    public ResponseEntity<ApiResponse<Void>> supprimer(@PathVariable Long id) {
        examenService.supprimer(id);
        return ResponseEntity.ok(ApiResponse.ok("Examen supprimé avec succès", null));
    }

    // pdf
    @PostMapping(value = "/{id}/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Importer le PDF du sujet d'examen", description = "Max 10 MB, format PDF uniquement")
    public ResponseEntity<ApiResponse<ExamenResponse>> importerPdf(
            @PathVariable Long id,
            @RequestParam("fichier") MultipartFile fichier) {

        return ResponseEntity.ok(
                ApiResponse.ok("PDF importé avec succès", examenService.importerPdf(id, fichier))
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
