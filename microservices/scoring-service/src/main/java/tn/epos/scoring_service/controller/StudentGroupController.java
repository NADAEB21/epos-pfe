package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.entities.StudentGroup;
import tn.epos.scoring_service.service.StudentGroupService;

import java.util.List;

@RestController
@RequestMapping("/api/student-groups")
public class StudentGroupController {

    @Autowired
    private StudentGroupService studentGroupService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<StudentGroup>>> getAllGroups() {
        return ResponseEntity.ok(ApiResponse.ok(studentGroupService.findAll()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<StudentGroup>> getGroupById(@PathVariable Long id) {
        return studentGroupService.findById(id)
                .map(group -> ResponseEntity.ok(ApiResponse.ok(group)))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Groupe non trouvé")));
    }

    @GetMapping("/lot/{lotId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<StudentGroup>>> getGroupsByLot(@PathVariable Long lotId) {
        return ResponseEntity.ok(ApiResponse.ok(studentGroupService.findByLotId(lotId)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<StudentGroup>> createGroup(@RequestBody StudentGroup group) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Groupe créé avec succès", studentGroupService.save(group)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<StudentGroup>> updateGroup(@PathVariable Long id, @RequestBody StudentGroup details) {
        return ResponseEntity.ok(ApiResponse.ok("Groupe mis à jour", studentGroupService.update(id, details)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Void>> deleteGroup(@PathVariable Long id) {
        studentGroupService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Groupe supprimé"));
    }
}