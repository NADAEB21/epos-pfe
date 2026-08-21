package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.common.exception.BusinessException;
import tn.epos.scoring_service.dto.BulkEnrolRequest;
import tn.epos.scoring_service.dto.BulkEnrolResult;
import tn.epos.scoring_service.dto.ParticipationDTO;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.service.EtudiantService;
import tn.epos.scoring_service.repositories.IEtudiantRepository;
import tn.epos.scoring_service.repositories.ILotRepository;
import tn.epos.scoring_service.service.ExamenParticipationService;

import java.util.List;

@RestController
@RequestMapping("/api/participations")
public class ExamenParticipationController {

    private static final String NOT_FOUND_MSG = "Participation non trouvée";

    @Autowired private ExamenParticipationService participationService;
    @Autowired private IEtudiantRepository        etudiantRepository;
    @Autowired private ILotRepository             lotRepository;

    @Autowired
    private EtudiantService etudiantService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<ParticipationDTO>>> getAllParticipations(
            @RequestParam(required = false) Long examenId) {
        List<ExamenParticipation> entities = (examenId != null)
                ? participationService.getByExamenId(examenId)
                : participationService.getAll();
        List<ParticipationDTO> dtos = entities.stream()
                .map(ParticipationDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<ParticipationDTO>> getById(@PathVariable Long id) {
        return participationService.getById(id)
                .map(p -> ResponseEntity.ok(ApiResponse.ok(ParticipationDTO.fromEntity(p))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(NOT_FOUND_MSG)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<ParticipationDTO>> create(@RequestBody ParticipationDTO dto) {
        ExamenParticipation entity = new ExamenParticipation();
        entity.setExamen_id(dto.examen_id());
        entity.setNum_echantillon(dto.num_echantillon());
        entity.setNote(dto.note());
        entity.setEst_present(dto.est_present());
        // Link the student: the DTO carries etudiantId but it was previously
        // dropped here, leaving every participation orphaned (roster showed no
        // names). Resolve and attach it so the FK is persisted.
        if (dto.etudiantId() != null) {
            entity.setEtudiant(etudiantService.getEtudiantById(dto.etudiantId())
                    .orElseThrow(() -> new BusinessException(
                            "Étudiant introuvable: " + dto.etudiantId())));
        }


        // Lier le lot
        if (dto.lotId() != null) {
            lotRepository.findById(dto.lotId())
                    .ifPresent(entity::setLot);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Participation enregistrée",
                        ParticipationDTO.fromEntity(participationService.save(entity))));
    }

    /**
     * #186 — inscription groupée : le responsable sélectionne N étudiants de
     * l'annuaire dans l'onglet Étudiants et les inscrit en un seul appel.
     * Mêmes droits que la création simple (POST /api/participations).
     */
    @PostMapping("/bulk")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<BulkEnrolResult>> createBulk(
            @RequestParam Long examenId,
            @RequestBody BulkEnrolRequest request) {
        BulkEnrolResult result = participationService.enrolBulk(examenId, request.etudiantIds());
        return ResponseEntity.ok(ApiResponse.ok("Inscription groupée terminée", result));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<ParticipationDTO>> update(
            @PathVariable Long id, @RequestBody ParticipationDTO dto) {
        // #274 — la mutation vit dans le SERVICE, pas ici. Le contrôleur chargeait l'entité,
        // la modifiait, puis appelait un `save` gardé : la garde répondait 403 et l'écriture
        // partait quand même (entité managée déjà sale, flushée par la transaction de la garde
        // elle-même). Mesuré en direct : note 3,25 → 19 sur un appel refusé.
        return participationService.update(id, dto.note(), dto.est_present())
                .map(saved -> ResponseEntity.ok(ApiResponse.ok("Participation mise à jour",
                        ParticipationDTO.fromEntity(saved))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(NOT_FOUND_MSG)));
    }

    // A participation is an exam enrolment, not a directory record: the same
    // RESPONSABLE_MATIERE who can POST one (enrol a student onto an exam they
    // author) must be able to undo it. Keeping DELETE SUPER_ADMIN-only while POST
    // is responsable-allowed was an add-without-remove asymmetry that left the
    // roster un-editable for the persona that owns it. DELETE /etudiants stays
    // admin-only — pruning the global student directory is a different concern.
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        if (participationService.getById(id).isPresent()) {
            participationService.delete(id);
            return ResponseEntity.ok(ApiResponse.ok("Participation supprimée"));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(NOT_FOUND_MSG));
    }
}