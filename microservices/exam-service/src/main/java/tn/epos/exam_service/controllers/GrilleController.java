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
import tn.epos.exam_service.dto.request.GrilleRequest;
import tn.epos.exam_service.dto.request.ItemRequest;
import tn.epos.common.dto.ApiResponse;
import tn.epos.exam_service.dto.response.GrilleResponse;
import tn.epos.exam_service.dto.response.ItemResponse;
import tn.epos.exam_service.dto.response.PageResponse;
import tn.epos.exam_service.services.GrilleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequiredArgsConstructor
@Tag(name = "Grilles & Critères", description = "Gestion des grilles d'évaluation et critères pondérés")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
public class GrilleController {
    private final GrilleService grilleService;

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    @PostMapping("/api/stations/{stationId}/grille")
    @Operation(summary = "Créer la grille d'une station",
            description = "Vous pouvez inclure les items directement dans la requête (création groupée)")
    public ResponseEntity<ApiResponse<GrilleResponse>> creer(
            @PathVariable Long stationId,
            @Valid @RequestBody GrilleRequest request) {

        GrilleResponse response = grilleService.creerPourStation(stationId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Grille créée avec succès", response));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    @PutMapping("/api/stations/{stationId}/grille")
    @Operation(summary = "Remplacer la grille d'une station (create-or-replace)",
            description = "Pose la grille de la station de façon idempotente : la crée si absente, "
                    + "la remplace intégralement (items compris) si présente. Évite le duo "
                    + "supprimer→créer côté client. Renvoie 200.")
    public ResponseEntity<ApiResponse<GrilleResponse>> remplacer(
            @PathVariable Long stationId,
            @Valid @RequestBody GrilleRequest request) {

        return ResponseEntity.ok(
                ApiResponse.ok("Grille remplacée avec succès",
                        grilleService.remplacerPourStation(stationId, request))
        );
    }

    @GetMapping("/api/stations/{stationId}/grille")
    @Operation(summary = "Récupérer la grille d'une station avec ses critères")
    public ResponseEntity<ApiResponse<GrilleResponse>> trouverParStation(@PathVariable Long stationId) {
        return ResponseEntity.ok(ApiResponse.ok(grilleService.trouverParStation(stationId)));
    }

    @GetMapping("/api/grilles/{id}")
    @Operation(summary = "Récupérer une grille par ID")
    public ResponseEntity<ApiResponse<GrilleResponse>> trouverParId(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(grilleService.trouverParId(id)));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    @PutMapping("/api/grilles/{id}")
    @Operation(summary = "Modifier une grille",
            description = "Modifie nom, noteMax et description. Pour les items, utilisez les endpoints /items")
    public ResponseEntity<ApiResponse<GrilleResponse>> modifier(
            @PathVariable Long id,
            @Valid @RequestBody GrilleRequest request) {

        return ResponseEntity.ok(
                ApiResponse.ok("Grille modifiée avec succès", grilleService.modifier(id, request))
        );
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    @DeleteMapping("/api/grilles/{id}")
    @Operation(summary = "Supprimer une grille et tous ses critères")
    public ResponseEntity<ApiResponse<Void>> supprimer(@PathVariable Long id) {
        grilleService.supprimer(id);
        return ResponseEntity.ok(ApiResponse.ok("Grille supprimée avec succès", null));
    }

    // items
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    @PostMapping("/api/grilles/{grilleId}/items")
    @Operation(summary = "Ajouter un critère à une grille",
            description = "BINAIRE : Fait/Non fait. NUMERIQUE : valeur entre 0 et valeurMax. "
                    + "L'ordre est attribué automatiquement. "
                    + "La somme des pondérations ne peut pas dépasser noteMax.")
    public ResponseEntity<ApiResponse<ItemResponse>> ajouterItem(
            @PathVariable Long grilleId,
            @Valid @RequestBody ItemRequest request) {

        ItemResponse response = grilleService.ajouterItem(grilleId, request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Critère ajouté avec succès", response));
    }

    @GetMapping("/api/grilles/{grilleId}/items")
    @Operation(summary = "Lister les critères d'une grille", description = "Triés par ordre croissant, avec pagination")
    public ResponseEntity<ApiResponse<PageResponse<ItemResponse>>> listerItems(
            @PathVariable Long grilleId,
            @PageableDefault(size = 50, sort = "ordre", direction = Sort.Direction.ASC)
            Pageable pageable) {

        Page<ItemResponse> items = grilleService.listerItems(grilleId, pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(items)));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    @PutMapping("/api/items/{id}")
    @Operation(summary = "Modifier un critère d'évaluation")
    public ResponseEntity<ApiResponse<ItemResponse>> modifierItem(
            @PathVariable Long id,
            @Valid @RequestBody ItemRequest request) {

        return ResponseEntity.ok(
                ApiResponse.ok("Critère modifié avec succès", grilleService.modifierItem(id, request))
        );
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    @DeleteMapping("/api/items/{id}")
    @Operation(summary = "Supprimer un critère", description = "Les critères suivants sont réordonnés automatiquement")
    public ResponseEntity<ApiResponse<Void>> supprimerItem(@PathVariable Long id) {
        grilleService.supprimerItem(id);
        return ResponseEntity.ok(ApiResponse.ok("Critère supprimé avec succès", null));
    }

    // sub criteria
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    @PostMapping("/api/items/{itemId}/sous-criteres")
    @Operation(summary = "Ajouter un sous-critère à un critère existant",
            description = "Un seul niveau de profondeur (#160). La somme des pondérations "
                    + "des sous-critères doit égaler la pondération du parent.")
    public ResponseEntity<ApiResponse<ItemResponse>> ajouterSousCritere(
            @PathVariable Long itemId,
            @Valid @RequestBody ItemRequest request) {
        ItemResponse response = grilleService.ajouterSousCritere(itemId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Sous-critère ajouté avec succès", response));
    }

    @GetMapping("/api/items/{itemId}/sous-criteres")
    @Operation(summary = "Lister les sous-critères d'un critère")
    public ResponseEntity<ApiResponse<List<ItemResponse>>> listerSousCriteres(@PathVariable Long itemId) {
        return ResponseEntity.ok(ApiResponse.ok(grilleService.listerSousCriteres(itemId)));
    }

    @GetMapping("/api/grilles/{grilleId}/items/feuilles")
    @Operation(summary = "Lister les items notables (feuilles) d'une grille",
            description = "Aplati la hiérarchie : ne renvoie que les critères sans sous-critères, "
                    + "ceux que l'évaluateur note réellement (#160).")
    public ResponseEntity<ApiResponse<List<ItemResponse>>> listerItemsFeuilles(@PathVariable Long grilleId) {
        return ResponseEntity.ok(ApiResponse.ok(grilleService.listerItemsFeuilles(grilleId)));
    }
}
