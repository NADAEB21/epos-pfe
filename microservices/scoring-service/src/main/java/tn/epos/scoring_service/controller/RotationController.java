package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.RotationDTO;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.repositories.IRotationRepository;
import tn.epos.scoring_service.repositories.IStudentGroupRepository;
import tn.epos.scoring_service.service.RotationService;

import java.util.List;

@RestController
@RequestMapping("/api/rotations")
public class RotationController {

    private static final String NOT_FOUND_MSG = "Rotation non trouvée";

    @Autowired private RotationService          rotationService;
    @Autowired private IStudentGroupRepository  studentGroupRepository;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<RotationDTO>>> getAll() {
        List<RotationDTO> dtos = rotationService.findAll().stream()
                .map(RotationDTO::fromEntity).toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<RotationDTO>> getById(@PathVariable Long id) {
        return rotationService.findById(id)
                .map(r -> ResponseEntity.ok(ApiResponse.ok(RotationDTO.fromEntity(r))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(NOT_FOUND_MSG)));
    }

    @GetMapping("/group/{groupId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<RotationDTO>>> getByGroup(@PathVariable Long groupId) {
        List<RotationDTO> dtos = rotationService.findByGroup(groupId).stream()
                .map(RotationDTO::fromEntity).toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @GetMapping("/station/{stationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<RotationDTO>>> getByStation(@PathVariable Long stationId) {
        List<RotationDTO> dtos = rotationService.findByStation(stationId).stream()
                .map(RotationDTO::fromEntity).toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<RotationDTO>> create(@RequestBody RotationDTO dto) {
        Rotation entity = new Rotation();
        entity.setEvaluateurId(dto.evaluateurId());
        entity.setStationId(dto.stationId());
        entity.setOrdrePassage(dto.ordrePassage());
        entity.setDebutCreneau(dto.debutCreneau());
        entity.setStatut(dto.statut());

        // Lier le StudentGroup → clé pour le planning et la chaîne JPQL de notation
        if (dto.studentGroupId() != null) {
            studentGroupRepository.findById(dto.studentGroupId())
                    .ifPresent(entity::setStudentGroup);
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Rotation créée",
                        RotationDTO.fromEntity(rotationService.save(entity))));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<RotationDTO>> update(
            @PathVariable Long id, @RequestBody RotationDTO dto) {
        Rotation entity = new Rotation();
        entity.setEvaluateurId(dto.evaluateurId());
        entity.setStationId(dto.stationId());
        entity.setOrdrePassage(dto.ordrePassage());
        entity.setDebutCreneau(dto.debutCreneau());
        entity.setStatut(dto.statut());
        if (dto.studentGroupId() != null) {
            studentGroupRepository.findById(dto.studentGroupId())
                    .ifPresent(entity::setStudentGroup);
        }
        return ResponseEntity.ok(ApiResponse.ok("Rotation mise à jour",
                RotationDTO.fromEntity(rotationService.update(id, entity))));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        rotationService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Rotation supprimée"));
    }
}