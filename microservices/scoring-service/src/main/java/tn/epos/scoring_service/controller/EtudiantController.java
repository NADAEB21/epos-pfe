package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tn.epos.scoring_service.entities.Etudiant;
import tn.epos.scoring_service.service.EtudiantService;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/etudiants")
@CrossOrigin("*") // Permet l'accès depuis un frontend (Angular, React, etc.)
public class EtudiantController {

    @Autowired
    private EtudiantService etudiantService;

    // GET : http://localhost:8083/api/etudiants
    @GetMapping
    public List<Etudiant> getAllEtudiants() {
        return etudiantService.getAllEtudiants();
    }

    // GET by ID : http://localhost:8083/api/etudiants/{id}
    @GetMapping("/{id}")
    public ResponseEntity<Etudiant> getEtudiantById(@PathVariable Long id) {
        return etudiantService.getEtudiantById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // POST : http://localhost:8083/api/etudiants
    @PostMapping
    public Etudiant createEtudiant(@RequestBody Etudiant etudiant) {
        return etudiantService.saveEtudiant(etudiant);
    }

    // PUT : http://localhost:8083/api/etudiants/{id}
    @PutMapping("/{id}")
    public ResponseEntity<Etudiant> updateEtudiant(@PathVariable Long id, @RequestBody Etudiant etudiant) {
        return etudiantService.getEtudiantById(id)
                .map(existingEtudiant -> {
                    existingEtudiant.setNom(etudiant.getNom());
                    existingEtudiant.setPrenom(etudiant.getPrenom());
                    existingEtudiant.setNumero_inscription(etudiant.getNumero_inscription());
                    Etudiant updated = etudiantService.saveEtudiant(existingEtudiant);
                    return ResponseEntity.ok(updated);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // DELETE : http://localhost:8083/api/etudiants/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEtudiant(@PathVariable Long id) {
        Optional<Etudiant> etudiant = etudiantService.getEtudiantById(id);

        if (etudiant.isPresent()) {
            etudiantService.deleteEtudiant(id);
            return ResponseEntity.noContent().build(); // type ResponseEntity<Void>
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}