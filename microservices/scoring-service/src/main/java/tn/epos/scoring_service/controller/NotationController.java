package tn.epos.scoring_service.controller;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.ExamenResultDTO;
import tn.epos.scoring_service.dto.NotationAdjustmentDTO;
import tn.epos.scoring_service.dto.NotationDTO; // New Import
import tn.epos.scoring_service.dto.ReajustementRequest;
import tn.epos.scoring_service.dto.StationGrilleSnapshotDTO;
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.service.NotationReajustementService;
import tn.epos.scoring_service.service.NotationService;

import java.util.List;

@RestController
@RequestMapping("/api/notations")
public class NotationController {

    private static final String NOT_FOUND_MSG = "Notation non trouvée";

    @Autowired
    private NotationService service;

    @Autowired
    private NotationReajustementService reajustementService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<NotationDTO>>> getAll() {
        List<NotationDTO> dtos = service.findAll().stream()
                .map(NotationDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<NotationDTO>> getById(@PathVariable Long id) {
        return service.findById(id)
                .map(n -> ResponseEntity.ok(ApiResponse.ok(NotationDTO.fromEntity(n))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(NOT_FOUND_MSG)));
    }

    @GetMapping("/assignment/{assignmentId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<NotationDTO>> getByAssignment(@PathVariable Long assignmentId) {
        return service.findByAssignment(assignmentId)
                .map(n -> ResponseEntity.ok(ApiResponse.ok(NotationDTO.fromEntity(n))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("Aucune notation pour cet assignment")));
    }

    @GetMapping("/station/{stationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<NotationDTO>>> getByStation(@PathVariable Long stationId) {
        List<NotationDTO> dtos = service.findByStation(stationId).stream()
                .map(NotationDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @GetMapping("/grille/{grilleId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<NotationDTO>>> getByGrille(@PathVariable Long grilleId) {
        List<NotationDTO> dtos = service.findByGrille(grilleId).stream()
                .map(NotationDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    // Résultats agrégés par étudiant pour un examen (issue #90). Monté sous le
    // préfixe /notations (et non /examens/{id}/results) car la gateway route
    // /api/v1/examens/** vers exam-service ; /api/v1/notations/** vient ici.
    @GetMapping("/examen/{examenId}/results")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<List<ExamenResultDTO>>> getResultsByExamen(@PathVariable Long examenId) {
        return ResponseEntity.ok(ApiResponse.ok(service.getResultatsByExamen(examenId)));
    }

    // #355 — les barèmes de l'examen tels qu'ils ont noté (exam_grille_snapshot,
    // ADR-0015), pour l'écran de délibération. Frère de /examen/{id}/results :
    // même préfixe gateway (/api/v1/notations/**), mêmes rôles, même garde de
    // matière (dans le service). Un examen sans snapshot (d'avant V19) renvoie
    // une liste vide — le web replie alors sur la grille vivante, en le disant.
    @GetMapping("/examen/{examenId}/grilles")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<List<StationGrilleSnapshotDTO>>> getGrillesByExamen(
            @PathVariable Long examenId) {
        return ResponseEntity.ok(ApiResponse.ok(service.getGrillesSnapshotByExamen(examenId)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'EVALUATEUR', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<NotationDTO>> create(@RequestBody NotationDTO dto) {
        Notation entity = new Notation();
        entity.setScore_final(dto.score_final());
        entity.setTemps_additionnel(dto.temps_additionnel());
        entity.setStationId(dto.stationId());
        entity.setGrilleId(dto.grilleId());
        entity.setVerouillee(false);

        NotationDTO saved = NotationDTO.fromEntity(service.save(entity, dto.assignmentId()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Évaluation initialisée", saved));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'EVALUATEUR', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<NotationDTO>> update(@PathVariable Long id, @RequestBody NotationDTO dto) {
        Notation updateData = new Notation();
        updateData.setScore_final(dto.score_final());
        updateData.setIs_synced(dto.is_synced());
        updateData.setTemps_additionnel(dto.temps_additionnel());

        NotationDTO updated = NotationDTO.fromEntity(service.update(id, updateData));
        return ResponseEntity.ok(ApiResponse.ok("Notation mise à jour", updated));
    }

    @PatchMapping("/{id}/verrouiller")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<NotationDTO>> lock(@PathVariable Long id) {
        NotationDTO locked = NotationDTO.fromEntity(service.verrouiller(id));
        return ResponseEntity.ok(ApiResponse.ok("Notation verrouillée définitivement", locked));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Notation supprimée"));
    }

    // Réajustement audité (ADR-0013 Part 2, #23/#135). Seul canal autorisé à
    // modifier une notation VERROUILLÉE — réclamation étudiante. RESPONSABLE +
    // ADMIN uniquement (PAS l'évaluateur : la supervision/réclamation relève du
    // responsable). motif obligatoire (@Valid). La notation reste verrouillée ;
    // chaque changement est tracé dans notation_adjustments.
    @PostMapping("/{id}/reajustement")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<NotationDTO>> reajuster(
            @PathVariable Long id, @Valid @RequestBody ReajustementRequest request) {
        reajustementService.reajuster(id, request);
        NotationDTO dto = service.findById(id).map(NotationDTO::fromEntity).orElse(null);
        return ResponseEntity.ok(ApiResponse.ok("Réajustement enregistré", dto));
    }

    // Historique des réajustements d'une notation (panneau responsable).
    @GetMapping("/{id}/reajustements")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<List<NotationAdjustmentDTO>>> historiqueReajustements(
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(reajustementService.historique(id)));
    }
}