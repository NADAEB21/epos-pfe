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
        Bilan bilan = new Bilan();

        int ligne = 0;
        for (ImportEtudiantRequest row : rows) {
            ligne++;
            try {
                traiterLigne(examenId, row, ligne, bilan);
            } catch (Exception ex) {
                bilan.errors++;
                bilan.results.add(ImportRowResult.of(ligne, row, "ERROR",
                        "Échec : " + ex.getMessage()));
            }
        }

        // #256 — une seule renumérotation, à la toute fin, quand on connaît le
        // fichier entier. Stamper ligne par ligne dans la boucle faisait repartir
        // les positions à 1 à chaque import (compteur local) : deux imports
        // successifs produisaient des positions EN DOUBLE, et la répartition en
        // lots comme les convocations retombaient sur l'alphabet pour départager.
        renumeroterListing(examenId, bilan.etudiantsDuFichier);

        // created rows are also enrolled; report enrolled as the new-enrolment total
        // minus the created ones so the four buckets sum to total cleanly.
        int enrolledExisting = bilan.enrolled - bilan.created;
        return new ImportResult(rows.size(), bilan.created, enrolledExisting, bilan.alreadyEnrolled,
                bilan.errors, bilan.emailsRenseignes, bilan.results);
    }

    /**
     * Compteurs et sorties d'un import, rassemblés pour que le traitement d'une
     * ligne puisse rester une méthode à part (sinon la boucle devait porter huit
     * variables mutables à la fois).
     */
    private static final class Bilan {
        private final List<ImportRowResult> results = new ArrayList<>();
        /** #256 — les étudiants de CE fichier, dans l'ordre du fichier. */
        private final List<Long> etudiantsDuFichier = new ArrayList<>();
        private int created;
        private int enrolled;
        private int alreadyEnrolled;
        private int errors;
        /**
         * #227 — adresses réellement renseignées par ce fichier. Sans ce compteur,
         * un réimport « juste pour ajouter les e-mails » se solde par « 0 créé,
         * 0 inscrit, 10 déjà inscrits » : indiscernable d'un import sans effet,
         * alors que c'est précisément ce que l'enseignant venait faire.
         */
        private int emailsRenseignes;
    }

    /** Une ligne du fichier : trouve-ou-crée l'étudiant, puis l'inscrit. */
    private void traiterLigne(Long examenId, ImportEtudiantRequest row, int ligne, Bilan bilan) {
        String numero = valeur(row.numero_inscription());
        String nom = valeur(row.nom());
        String prenom = valeur(row.prenom());
        String email = valeur(row.email());

        if (numero.isEmpty() || nom.isEmpty()) {
            bilan.errors++;
            bilan.results.add(ImportRowResult.of(ligne, row, "ERROR",
                    "Numéro d'inscription et nom sont obligatoires."));
            return;
        }

        // Find-or-create the directory record. numero_inscription is not unique
        // server-side, so reuse the first existing match rather than forking the
        // directory; never overwrite an existing name.
        List<Etudiant> existing = etudiantRepository.findByNumeroInscription(numero);
        boolean newStudent = existing.isEmpty();
        Etudiant etudiant;
        boolean emailRenseigne = false;
        if (newStudent) {
            etudiant = creerEtudiant(numero, nom, prenom, email);
        } else {
            etudiant = existing.get(0);
            emailRenseigne = renseignerEmail(etudiant, email);
            if (emailRenseigne) {
                bilan.emailsRenseignes++;
            }
        }

        if (participationRepository.existsByExamenAndEtudiant(examenId, etudiant.getId())) {
            // #256 — déjà inscrit : sa position est refaite en fin d'import
            // (renumeroterListing), pas ici. On note juste son rang dans le fichier.
            bilan.etudiantsDuFichier.add(etudiant.getId());
            bilan.alreadyEnrolled++;
            // #227 — dire ce qui s'est VRAIMENT passé. « Déjà inscrit » tout court
            // laissait croire que la ligne n'avait rien fait.
            bilan.results.add(ImportRowResult.of(ligne, row, "ALREADY_ENROLLED",
                    emailRenseigne
                            ? "Déjà inscrit — adresse e-mail mise à jour."
                            : "Déjà inscrit à cet examen."));
            return;
        }

        ExamenParticipation p = new ExamenParticipation();
        p.setExamen_id(examenId);
        p.setEtudiant(etudiant);
        participationRepository.save(p);
        bilan.etudiantsDuFichier.add(etudiant.getId());
        bilan.enrolled++;

        if (newStudent) {
            bilan.created++;
            bilan.results.add(ImportRowResult.of(ligne, row, "CREATED",
                    "Étudiant créé et inscrit."));
        } else {
            bilan.results.add(ImportRowResult.of(ligne, row, "ENROLLED",
                    emailRenseigne
                            ? "Étudiant existant inscrit — adresse e-mail mise à jour."
                            : "Étudiant existant inscrit."));
        }
    }

    private Etudiant creerEtudiant(String numero, String nom, String prenom, String email) {
        Etudiant e = new Etudiant();
        e.setNumero_inscription(numero);
        e.setNom(nom);
        e.setPrenom(prenom);
        e.setEmail(email);
        return etudiantRepository.save(e);
    }

    /**
     * #227 — le fichier renseigne l'adresse. On ne l'efface JAMAIS depuis un
     * import : une colonne vide veut dire « je ne sais pas », pas « supprime » ;
     * l'effacement explicite passe par la fiche de l'étudiant.
     *
     * @return true si l'adresse a réellement changé
     */
    private boolean renseignerEmail(Etudiant etudiant, String email) {
        String ancien = valeur(etudiant.getEmail());
        if (email.isEmpty() || email.equals(ancien)) {
            return false;
        }
        etudiant.setEmail(email);
        etudiantRepository.save(etudiant);
        return true;
    }

    private static String valeur(String brut) {
        return brut == null ? "" : brut.trim();
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

        List<ExamenParticipation> duFichier = participationsDuFichier(toutes, etudiantsDuFichier);
        Set<Long> idsDuFichier = new HashSet<>();
        for (ExamenParticipation p : duFichier) {
            idsDuFichier.add(p.getId());
        }
        List<ExamenParticipation> dejaPositionnes = dejaPositionnes(toutes);

        // LE test qui distingue les deux intentions : ce fichier remplace-t-il
        // le listing, ou le complète-t-il ?
        boolean fichierCouvreToutLeListing = dejaPositionnes.stream()
                .allMatch(p -> idsDuFichier.contains(p.getId()));

        // Réimport du listing corrigé → le fichier fait foi de bout en bout.
        // Sinon (retardataires) → les inscrits gardent leur ordre, les nouveaux
        // venus du fichier s'ajoutent à la suite, dans l'ordre du fichier.
        List<ExamenParticipation> ordreFinal = fichierCouvreToutLeListing
                ? new ArrayList<>(duFichier)
                : concatSansDoublon(dejaPositionnes, duFichier);

        int position = 0;
        for (ExamenParticipation p : ordreFinal) {
            p.setOrdre_import(++position);
        }
        participationRepository.saveAll(ordreFinal);
    }

    /**
     * Les participations visées par ce fichier, dans l'ordre du fichier et sans
     * doublon : un même numéro d'inscription peut apparaître deux fois, la 1re
     * occurrence fixe la position et les suivantes sont ignorées plutôt que de
     * décaler tout le monde.
     */
    private List<ExamenParticipation> participationsDuFichier(List<ExamenParticipation> toutes,
                                                              List<Long> etudiantsDuFichier) {
        Map<Long, ExamenParticipation> parEtudiant = new HashMap<>();
        for (ExamenParticipation p : toutes) {
            if (p.getEtudiant() != null) {
                parEtudiant.put(p.getEtudiant().getId(), p);
            }
        }
        List<ExamenParticipation> duFichier = new ArrayList<>();
        Set<Long> vus = new HashSet<>();
        for (Long etudiantId : etudiantsDuFichier) {
            ExamenParticipation p = parEtudiant.get(etudiantId);
            if (p != null && vus.add(p.getId())) {
                duFichier.add(p);
            }
        }
        return duFichier;
    }

    /**
     * Les inscrits déjà positionnés par un import précédent, dans leur ordre.
     * Les ajouts manuels ({@code ordre_import} null) sont hors jeu : ils doivent
     * le RESTER, c'est le signal dont dépend LotAssignmentService pour les placer
     * après le fichier.
     */
    private List<ExamenParticipation> dejaPositionnes(List<ExamenParticipation> toutes) {
        List<ExamenParticipation> out = new ArrayList<>();
        for (ExamenParticipation p : toutes) {
            if (p.getOrdre_import() != null) {
                out.add(p);
            }
        }
        out.sort(Comparator.comparing(ExamenParticipation::getOrdre_import)
                .thenComparing(ExamenParticipation::getId));
        return out;
    }

    private List<ExamenParticipation> concatSansDoublon(List<ExamenParticipation> premiers,
                                                        List<ExamenParticipation> suivants) {
        List<ExamenParticipation> out = new ArrayList<>();
        Set<Long> placees = new HashSet<>();
        for (ExamenParticipation p : premiers) {
            if (placees.add(p.getId())) {
                out.add(p);
            }
        }
        for (ExamenParticipation p : suivants) {
            if (placees.add(p.getId())) {
                out.add(p);
            }
        }
        return out;
    }
}