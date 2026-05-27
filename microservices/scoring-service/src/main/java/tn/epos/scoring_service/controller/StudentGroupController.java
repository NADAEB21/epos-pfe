package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.StudentGroupDTO; // New Import
import tn.epos.scoring_service.entities.StudentGroup;
import tn.epos.scoring_service.service.StudentGroupService;

import java.util.List;

@RestController
@RequestMapping("/api/student-groups")
public class StudentGroupController {

    private static final String NOT_FOUND_MSG = "Groupe non trouvé";

    @Autowired
    private StudentGroupService studentGroupService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<StudentGroupDTO>>> getAllGroups() {
        List<StudentGroupDTO> dtos = studentGroupService.findAll().stream()
                .map(StudentGroupDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<StudentGroupDTO>> getGroupById(@PathVariable Long id) {
        return studentGroupService.findById(id)
                .map(group -> ResponseEntity.ok(ApiResponse.ok(StudentGroupDTO.fromEntity(group))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(NOT_FOUND_MSG)));
    }

    @GetMapping("/lot/{lotId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<StudentGroupDTO>>> getGroupsByLot(@PathVariable Long lotId) {
        List<StudentGroupDTO> dtos = studentGroupService.findByLotId(lotId).stream()
                .map(StudentGroupDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<StudentGroupDTO>> createGroup(@RequestBody StudentGroupDTO dto) {
        StudentGroup entity = new StudentGroup();
        entity.setNumeroGroupe(dto.numeroGroupe());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Groupe créé avec succès",
                        StudentGroupDTO.fromEntity(studentGroupService.save(entity))));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<StudentGroupDTO>> updateGroup(@PathVariable Long id,
            @RequestBody StudentGroupDTO dto) {
        StudentGroup entity = new StudentGroup();
        entity.setNumeroGroupe(dto.numeroGroupe());
        return ResponseEntity.ok(ApiResponse.ok("Groupe mis à jour",
                StudentGroupDTO.fromEntity(studentGroupService.update(id, entity))));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Void>> deleteGroup(@PathVariable Long id) {
        studentGroupService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Groupe supprimé"));
    }
}