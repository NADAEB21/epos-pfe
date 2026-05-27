package tn.epos.exam_service.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import tn.epos.exam_service.dto.request.StationRequest;
import tn.epos.common.dto.ApiResponse;
import tn.epos.exam_service.dto.response.PageResponse;
import tn.epos.exam_service.dto.response.StationResponse;
import tn.epos.exam_service.services.StationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Stations", description = "Gestion des stations d'évaluation")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
public class StationController {
    private final StationService stationService;

    @PostMapping("/api/examens/{examenId}/stations")
    @Operation(summary = "Ajouter une station à un examen",
            description = "L'ordre est attribué automatiquement")
    public ResponseEntity<ApiResponse<StationResponse>> ajouter(
            @PathVariable Long examenId,
            @Valid @RequestBody StationRequest request) {

        StationResponse response = stationService.ajouter(examenId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Station ajoutée avec succès", response));
    }

    @GetMapping("/api/examens/{examenId}/stations")
    @Operation(summary = "Lister les stations d'un examen", description = "Triées par ordre croissant, avec pagination")
    public ResponseEntity<ApiResponse<PageResponse<StationResponse>>> lister(
            @PathVariable Long examenId,
            @PageableDefault(size = 20, sort = "ordre", direction = Sort.Direction.ASC)
            Pageable pageable) {

        Page<StationResponse> stations = stationService.listerParExamen(examenId, pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(stations)));
    }

    @GetMapping("/api/stations/{id}")
    @Operation(summary = "Détail d'une station", description = "Inclut les infos de la grille si elle existe")
    public ResponseEntity<ApiResponse<StationResponse>> trouverParId(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(stationService.trouverParId(id)));
    }

    @PutMapping("/api/stations/{id}")
    @Operation(summary = "Modifier une station")
    public ResponseEntity<ApiResponse<StationResponse>> modifier(
            @PathVariable Long id,
            @Valid @RequestBody StationRequest request) {

        return ResponseEntity.ok(
                ApiResponse.ok("Station modifiée avec succès", stationService.modifier(id, request))
        );
    }

    @PatchMapping("/api/stations/{id}/evaluateurs")
    @Operation(summary = "Affecter des évaluateurs à une station",
            description = "Remplace la liste des évaluateurs. Passer une liste vide pour tout retirer.")
    public ResponseEntity<ApiResponse<StationResponse>> affecterEvaluateurs(
            @PathVariable Long id,
            @RequestBody List<Long> evaluateurIds) {

        return ResponseEntity.ok(
                ApiResponse.ok("Évaluateurs affectés",
                        stationService.affecterEvaluateurs(id, evaluateurIds))
        );
    }

    @DeleteMapping("/api/stations/{id}")
    @Operation(summary = "Supprimer une station", description = "Supprime aussi la grille associée en cascade")
    public ResponseEntity<ApiResponse<Void>> supprimer(@PathVariable Long id) {
        stationService.supprimer(id);
        return ResponseEntity.ok(ApiResponse.ok("Station supprimée avec succès", null));
    }
}
