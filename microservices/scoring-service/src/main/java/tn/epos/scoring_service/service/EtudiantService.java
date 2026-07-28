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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
        // #227 — combien d'adresses ce fichier a réellement renseignées. Sans ce
        // compteur, un réimport « juste pour ajouter les e-mails » se solde par
        // « 0 créé, 0 inscrit, 10 déjà inscrits » : indiscernable d'un import
        // sans effet, alors que c'est précisément ce que l'enseignant venait faire.
        int emailsRenseignes = 0;

        // #256 — les étudiants de CE fichier, dans l'ordre du fichier. Sert à
        // renuméroter tout l'examen une fois l'import terminé (voir plus bas).
        List<Long> etudiantsDuFichier = new ArrayList<>();

        int ligne = 0;
        for (ImportEtudiantRequest row : rows) {
            ligne++;
            boolean emailRenseigne = false;
            try {
                String numero = row.numero_inscription() != null ? row.numero_inscription().trim() : "";
                String nom = row.nom() != null ? row.nom().trim() : "";
                String prenom = row.prenom() != null ? row.prenom().trim() : "";
                String email = row.email() != null ? row.email().trim() : ""; // #227 Extracted

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
                    e.setEmail(email); // #227 Persisted for new student
                    etudiant = etudiantRepository.save(e);
                } else {
                    etudiant = existing.get(0);
                    // #227 — le fichier renseigne l'adresse. On ne l'efface JAMAIS
                    // depuis un import (une colonne vide = "je ne sais pas", pas
                    // "supprime") ; l'effacement explicite passe par la fiche.
                    String ancien = etudiant.getEmail() == null ? "" : etudiant.getEmail().trim();
                    if (!email.isEmpty() && !email.equals(ancien)) {
                        etudiant.setEmail(email);
                        etudiantRepository.save(etudiant);
                        emailRenseigne = true;
                        emailsRenseignes++;
                    }
                }

                if (participationRepository.existsByExamenAndEtudiant(examenId, etudiant.getId())) {
                    // #256 — déjà inscrit : sa position est refaite en fin d'import
                    // (renumeroterListing), pas ici. On note juste son rang dans le fichier.
                    etudiantsDuFichier.add(etudiant.getId());
                    alreadyEnrolled++;
                    // #227 — dire ce qui s'est VRAIMENT passé. « Déjà inscrit »
                    // tout court laissait croire que la ligne n'avait rien fait.
                    results.add(ImportRowResult.of(ligne, row, "ALREADY_ENROLLED",
                            emailRenseigne
                                    ? "Déjà inscrit — adresse e-mail mise à jour."
                                    : "Déjà inscrit à cet examen."));
                    continue;
                }

                ExamenParticipation p = new ExamenParticipation();
                p.setExamen_id(examenId);
                p.setEtudiant(etudiant);
                participationRepository.save(p);
                etudiantsDuFichier.add(etudiant.getId());

                enrolled++;
                if (newStudent) {
                    created++;
                    results.add(ImportRowResult.of(ligne, row, "CREATED",
                            "Étudiant créé et inscrit."));
                } else {
                    results.add(ImportRowResult.of(ligne, row, "ENROLLED",
                            emailRenseigne
                                    ? "Étudiant existant inscrit — adresse e-mail mise à jour."
                                    : "Étudiant existant inscrit."));
                }
            } catch (Exception ex) {
                errors++;
                results.add(ImportRowResult.of(ligne, row, "ERROR",
                        "Échec : " + ex.getMessage()));
            }
        }

        // #256 — une seule renumérotation, à la toute fin, quand on connaît le
        // fichier entier. Stamper ligne par ligne dans la boucle faisait repartir
        // les positions à 1 à chaque import (compteur local) : deux imports
        // successifs produisaient des positions EN DOUBLE, et la répartition en
        // lots comme les convocations retombaient sur l'alphabet pour départager.
        renumeroterListing(examenId, etudiantsDuFichier);

        // created rows are also enrolled; report enrolled as the new-enrolment total
        // minus the created ones so the four buckets sum to total cleanly.
        int enrolledExisting = enrolled - created;
        return new ImportResult(rows.size(), created, enrolledExisting, alreadyEnrolled, errors,
                emailsRenseignes, results);
    }

    /**
     * #256 — rejoue l'ordre officiel du listing sur TOUT l'examen après un import.
     *
     * <p>Règle retenue (décision Nada, 2026-07-28), en DEUX cas, parce que le même
     * bouton sert à deux intentions opposées :
     * <ul>
     *   <li><b>Le fichier couvre tous les inscrits déjà positionnés</b> — c'est un
     *       réimport du listing corrigé : <b>le nouveau fichier fait foi</b>, ses
     *       étudiants prennent 1..N dans l'ordre du fichier.</li>
     *   <li><b>Le fichier n'en couvre qu'une partie</b> — c'est un ajout (des
     *       retardataires) : les inscrits gardent leur ordre, et les nouveaux
     *       du fichier sont <b>ajoutés à la suite</b>. Les faire passer devant
     *       serait absurde, et c'est ce que ferait une règle « le dernier fichier
     *       gagne » appliquée aveuglément.</li>
     * </ul>
     * Le test est décidable sur les données (le fichier contient-il tout le monde ?)
     * — aucune heuristique. Dans les deux cas les positions restent <b>uniques</b>,
     * ce qui était le vrai défaut : deux imports successifs produisaient la même
     * position deux fois, et l'alphabet départageait l'ordre des convocations
     * comme la composition des lots.
     *
     * <p>Les <b>ajouts manuels</b> gardent délibérément {@code ordre_import} à
     * {@code null} : c'est le signal, respecté par {@code LotAssignmentService},
     * qu'ils passent après le fichier. Seules les positions issues d'un import
     * sont réécrites.
     *
     * <p>Écrit explicitement via {@code saveAll} : l'import n'est volontairement
     * pas transactionnel (voir {@link #importStudents}), donc rien n'est flushé
     * tout seul.
     */
    private void renumeroterListing(Long examenId, List<Long> etudiantsDuFichier) {
        List<ExamenParticipation> toutes = participationRepository.findByExamenId(examenId);

        Map<Long, ExamenParticipation> parEtudiant = new HashMap<>();
        for (ExamenParticipation p : toutes) {
            if (p.getEtudiant() != null) {
                parEtudiant.put(p.getEtudiant().getId(), p);
            }
        }

        // Les participations visées par ce fichier, sans doublon : un même numéro
        // d'inscription peut apparaître deux fois, la 1re occurrence fait foi.
        List<ExamenParticipation> duFichier = new ArrayList<>();
        Set<Long> idsDuFichier = new HashSet<>();
        for (Long etudiantId : etudiantsDuFichier) {
            ExamenParticipation p = parEtudiant.get(etudiantId);
            if (p != null && idsDuFichier.add(p.getId())) {
                duFichier.add(p);
            }
        }

        // Les inscrits déjà positionnés par un import précédent. Les ajouts
        // manuels (ordre null) sont hors jeu : ils doivent le RESTER.
        List<ExamenParticipation> dejaPositionnes = new ArrayList<>();
        for (ExamenParticipation p : toutes) {
            if (p.getOrdre_import() != null) {
                dejaPositionnes.add(p);
            }
        }
        dejaPositionnes.sort(Comparator.comparing(ExamenParticipation::getOrdre_import)
                .thenComparing(ExamenParticipation::getId));

        // LE test qui distingue les deux intentions : ce fichier remplace-t-il
        // le listing, ou le complète-t-il ?
        boolean fichierCouvreToutLeListing = dejaPositionnes.stream()
                .allMatch(p -> idsDuFichier.contains(p.getId()));

        List<ExamenParticipation> ordreFinal = new ArrayList<>();
        if (fichierCouvreToutLeListing) {
            // Réimport du listing corrigé → le fichier fait foi, de bout en bout.
            ordreFinal.addAll(duFichier);
        } else {
            // Ajout de retardataires → les inscrits gardent leur ordre, les
            // nouveaux venus du fichier s'ajoutent à la suite, dans l'ordre du fichier.
            Set<Long> placees = new HashSet<>();
            for (ExamenParticipation p : dejaPositionnes) {
                if (placees.add(p.getId())) {
                    ordreFinal.add(p);
                }
            }
            for (ExamenParticipation p : duFichier) {
                if (placees.add(p.getId())) {
                    ordreFinal.add(p);
                }
            }
        }

        int position = 0;
        for (ExamenParticipation p : ordreFinal) {
            p.setOrdre_import(++position);
        }
        participationRepository.saveAll(ordreFinal);
    }
}