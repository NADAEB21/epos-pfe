package tn.epos.scoring_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tn.epos.scoring_service.dto.ImportEtudiantRequest;
import tn.epos.scoring_service.dto.ImportResult;
import tn.epos.scoring_service.dto.ImportRowResult;
import tn.epos.scoring_service.entities.Etudiant;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.repositories.IEtudiantRepository;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class EtudiantService {

    @Autowired
    private IEtudiantRepository etudiantRepository;

    @Autowired
    private IExamenParticipationRepository participationRepository;

    public List<Etudiant> getAllEtudiants() {
        return etudiantRepository.findAll();
    }

    public Optional<Etudiant> getEtudiantById(Long id) {
        return etudiantRepository.findById(id);
    }

    public Etudiant saveEtudiant(Etudiant etudiant) {
        return etudiantRepository.save(etudiant);
    }

    public void deleteEtudiant(Long id) {
        etudiantRepository.deleteById(id);
    }

    /**
     * Bulk import: for each parsed row, find-or-create the student by
     * numero_inscription, then enrol them on {@code examenId}. Mirrors the
     * single-row createEtudiant→createParticipation flow but tolerant per-row.
     *
     * <p>Deliberately NOT wrapped in one transaction: each row is processed and
     * persisted independently so a single bad row (or a unique-constraint trip)
     * can't roll back the whole batch. Every row produces an {@link ImportRowResult}
     * so the caller can render a line-by-line outcome.
     */
    public ImportResult importStudents(Long examenId, List<ImportEtudiantRequest> rows) {
        List<ImportRowResult> results = new ArrayList<>();
        int created = 0;
        int enrolled = 0;
        int alreadyEnrolled = 0;
        int errors = 0;

        int ligne = 0;
        for (ImportEtudiantRequest row : rows) {
            ligne++;
            try {
                String numero = row.numero_inscription() != null ? row.numero_inscription().trim() : "";
                String nom = row.nom() != null ? row.nom().trim() : "";
                String prenom = row.prenom() != null ? row.prenom().trim() : "";

                if (numero.isEmpty() || nom.isEmpty()) {
                    errors++;
                    results.add(ImportRowResult.of(ligne, row, "ERROR",
                            "Numéro d'inscription et nom sont obligatoires."));
                    continue;
                }

                // Find-or-create the directory record. numero_inscription is not
                // unique server-side, so reuse the first existing match rather than
                // forking the directory; never overwrite an existing name.
                List<Etudiant> existing = etudiantRepository.findByNumeroInscription(numero);
                boolean newStudent = existing.isEmpty();
                Etudiant etudiant;
                if (newStudent) {
                    Etudiant e = new Etudiant();
                    e.setNumero_inscription(numero);
                    e.setNom(nom);
                    e.setPrenom(prenom);
                    etudiant = etudiantRepository.save(e);
                } else {
                    etudiant = existing.get(0);
                }

                if (participationRepository.existsByExamenAndEtudiant(examenId, etudiant.getId())) {
                    // #256 — même déjà inscrit, on RAFRAÎCHIT sa position : si l'enseignant
                    // réimporte un fichier corrigé, c'est le NOUVEAU listing qui fait foi.
                    final int ligneCourante = ligne;
                    participationRepository.findByEtudiantIdAndExamenId(etudiant.getId(), examenId)
                            .ifPresent(pp -> { pp.setOrdre_import(ligneCourante); participationRepository.save(pp); });
                    alreadyEnrolled++;
                    results.add(ImportRowResult.of(ligne, row, "ALREADY_ENROLLED",
                            "Déjà inscrit à cet examen."));
                    continue;
                }

                ExamenParticipation p = new ExamenParticipation();
                p.setExamen_id(examenId);
                p.setEtudiant(etudiant);
                // #256 — l'ordre du fichier EST l'ordre officiel du listing : persisté ici,
                // au seul endroit qui le connaît. La répartition en lots trie dessus.
                p.setOrdre_import(ligne);
                participationRepository.save(p);

                enrolled++;
                if (newStudent) {
                    created++;
                    results.add(ImportRowResult.of(ligne, row, "CREATED",
                            "Étudiant créé et inscrit."));
                } else {
                    results.add(ImportRowResult.of(ligne, row, "ENROLLED",
                            "Étudiant existant inscrit."));
                }
            } catch (Exception ex) {
                errors++;
                results.add(ImportRowResult.of(ligne, row, "ERROR",
                        "Échec : " + ex.getMessage()));
            }
        }

        // created rows are also enrolled; report enrolled as the new-enrolment total
        // minus the created ones so the four buckets sum to total cleanly.
        int enrolledExisting = enrolled - created;
        return new ImportResult(rows.size(), created, enrolledExisting, alreadyEnrolled, errors, results);
    }
}
