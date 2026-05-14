package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.service.ExamenParticipationService;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/participations")
public class ExamenParticipationController {

    @Autowired
    private ExamenParticipationService participationService;

    // GET : http://localhost:8083/api/participations
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public List<ExamenParticipation> getAllParticipations() {
        return participationService.getAll();
    }

    // GET by ID : http://localhost:8083/api/participations/{id}
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ExamenParticipation> getById(@PathVariable Long id) {
        Optional<ExamenParticipation> participation = participationService.getById(id);
        return participation.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // POST : http://localhost:8083/api/participations
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ExamenParticipation create(@RequestBody ExamenParticipation participation) {
        return participationService.save(participation);
    }

    // PUT : http://localhost:8083/api/participations/{id}
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ExamenParticipation> update(@PathVariable Long id,
                                                      @RequestBody ExamenParticipation participation) {
        Optional<ExamenParticipation> existing = participationService.getById(id);
        if (existing.isPresent()) {
            ExamenParticipation p = existing.get();
            p.setEtudiant(participation.getEtudiant());
            p.setNote(participation.getNote());
            // On ignore l'examen si tu n'as pas ce champ
            ExamenParticipation updated = participationService.save(p);
            return ResponseEntity.ok(updated);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    // DELETE : http://localhost:8083/api/participations/{id}
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Optional<ExamenParticipation> existing = participationService.getById(id);
        if (existing.isPresent()) {
            participationService.delete(id);
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}