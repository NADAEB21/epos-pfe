package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tn.epos.scoring_service.entities.StudentGroup;
import tn.epos.scoring_service.service.StudentGroupService;

import java.util.List;

@RestController
@RequestMapping("/api/student-groups")
@CrossOrigin("*")
public class StudentGroupController {

    @Autowired
    private StudentGroupService studentGroupService;

    // GET : tous les groupes
    @GetMapping
    public List<StudentGroup> getAllGroups() {
        return studentGroupService.findAll();
    }

    // GET : groupe par ID
    @GetMapping("/{id}")
    public ResponseEntity<StudentGroup> getGroupById(@PathVariable Long id) {
        return studentGroupService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // GET : groupes par Lot
    @GetMapping("/lot/{lotId}")
    public List<StudentGroup> getGroupsByLot(@PathVariable Long lotId) {
        return studentGroupService.findByLotId(lotId);
    }

    // POST : créer un groupe
    @PostMapping
    public StudentGroup createGroup(@RequestBody StudentGroup group) {
        return studentGroupService.save(group);
    }

    // PUT : modifier un groupe
    @PutMapping("/{id}")
    public ResponseEntity<StudentGroup> updateGroup(@PathVariable Long id, @RequestBody StudentGroup details) {
        try {
            return ResponseEntity.ok(studentGroupService.update(id, details));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // DELETE : supprimer un groupe
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long id) {
        studentGroupService.delete(id);
        return ResponseEntity.ok().build();
    }
}