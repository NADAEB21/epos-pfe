package tn.epos.auth_service.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.auth_service.audit.AuditAction;
import tn.epos.auth_service.audit.AuditService;
import tn.epos.auth_service.dto.MatiereImportResult;
import tn.epos.auth_service.dto.MatiereImportRow;
import tn.epos.auth_service.dto.MatiereRequest;
import tn.epos.auth_service.dto.MatiereResponse;
import tn.epos.auth_service.entity.Matiere;
import tn.epos.auth_service.exception.MatiereConflictException;
import tn.epos.auth_service.exception.MatiereNotFoundException;
import tn.epos.auth_service.repository.MatiereRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * #134 — le catalogue des matières cesse d'être géré au SQL.
 *
 * <p>Doctrine (ADR-0018 D5) : le catalogue est un des domaines d'écriture
 * légitimes du SUPER_ADMIN. Verbes : créer, renommer, retirer, rouvrir,
 * importer en lot. <b>Jamais de suppression</b> : {@code matiere_id} traverse
 * les services en clé logique sans contrainte SQL (ADR-0006) — un DELETE
 * orphelinerait silencieusement les examens passés, dont les résultats
 * afficheraient « Matière 7 » au lieu d'un nom.
 *
 * <p>Le retrait suit la doctrine de #289 (retrait d'un compte) : motivé,
 * attribué, réversible — une matière qui ferme est un fait administratif qui
 * se raconte, pas une ligne qu'on efface.
 */
@Service
@RequiredArgsConstructor
public class MatiereService {

    private final MatiereRepository matiereRepository;
    private final AuditService auditService;
    private final Clock clock;

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * Liste COMPLÈTE, matières retirées comprises (drapeau {@code active}).
     * Filtrer côté serveur casserait l'affichage des libellés historiques :
     * un examen passé dans une matière retirée doit garder son nom. Ce sont
     * les pickers, côté client, qui excluent les retirées.
     */
    @Transactional(readOnly = true)
    public List<MatiereResponse> list() {
        return matiereRepository.findAll().stream()
                .map(MatiereResponse::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Create / rename
    // -------------------------------------------------------------------------

    @Transactional
    public MatiereResponse creer(MatiereRequest request, Long acteurId, String acteurEmail) {
        String code = request.code().trim();
        String libelle = request.libelle().trim();
        verifierCodeDisponible(code, null);

        Matiere saved = matiereRepository.save(Matiere.builder()
                .code(code)
                .libelle(libelle)
                .build());

        auditService.logAttribue(null, acteurEmail, AuditAction.MATIERE_CREATED,
                code + " — " + libelle, null, acteurId);
        return MatiereResponse.from(saved);
    }

    /**
     * Renommage (code et/ou libellé). Les références restent intactes : tout
     * pointe sur l'id, jamais sur le code — corriger « Pharmacolgie » ne casse
     * ni les rôles ni les examens.
     */
    @Transactional
    public MatiereResponse modifier(Long id, MatiereRequest request,
                                    Long acteurId, String acteurEmail) {
        Matiere matiere = findOrThrow(id);
        String code = request.code().trim();
        String libelle = request.libelle().trim();
        verifierCodeDisponible(code, id);

        String avant = matiere.getCode() + " — " + matiere.getLibelle();
        matiere.setCode(code);
        matiere.setLibelle(libelle);
        Matiere saved = matiereRepository.save(matiere);

        auditService.logAttribue(null, acteurEmail, AuditAction.MATIERE_UPDATED,
                avant + " → " + code + " — " + libelle, null, acteurId);
        return MatiereResponse.from(saved);
    }

    // -------------------------------------------------------------------------
    // Retire / reactivate — jamais de DELETE
    // -------------------------------------------------------------------------

    /**
     * Retire la matière du catalogue actif : elle disparaît des listes de
     * choix (création d'examen, nomination d'un responsable) mais son nom
     * continue d'exister pour l'historique. Les rôles déjà portés sur elle ne
     * sont PAS touchés : retirer une matière est un acte de catalogue, pas une
     * révocation de personnes — et il est réversible.
     */
    @Transactional
    public MatiereResponse retirer(Long id, String motif, Long acteurId, String acteurEmail) {
        Matiere matiere = findOrThrow(id);
        if (Boolean.FALSE.equals(matiere.getActive())) {
            throw new MatiereConflictException(
                    "La matière « " + matiere.getLibelle() + " » est déjà retirée.");
        }

        matiere.setActive(false);
        matiere.setRetiredAt(LocalDateTime.now(clock));
        matiere.setRetiredBy(acteurId);
        matiere.setRetirementMotif(motif);
        Matiere saved = matiereRepository.save(matiere);

        auditService.logAttribue(null, acteurEmail, AuditAction.MATIERE_RETIRED,
                matiere.getCode() + " — " + motif, null, acteurId);
        return MatiereResponse.from(saved);
    }

    /** Rouvre une matière retirée — le chemin de retour, comme #289 pour un compte. */
    @Transactional
    public MatiereResponse reactiver(Long id, String motif, Long acteurId, String acteurEmail) {
        Matiere matiere = findOrThrow(id);
        if (!Boolean.FALSE.equals(matiere.getActive())) {
            throw new MatiereConflictException(
                    "La matière « " + matiere.getLibelle() + " » est déjà active.");
        }

        matiere.setActive(true);
        matiere.setRetiredAt(null);
        matiere.setRetiredBy(null);
        matiere.setRetirementMotif(null);
        Matiere saved = matiereRepository.save(matiere);

        auditService.logAttribue(null, acteurEmail, AuditAction.MATIERE_REACTIVATED,
                matiere.getCode() + " — " + motif, null, acteurId);
        return MatiereResponse.from(saved);
    }

    // -------------------------------------------------------------------------
    // Bulk import
    // -------------------------------------------------------------------------

    /**
     * Import en lot, verdict par ligne. Chaque ligne valide est enregistrée
     * même si une autre échoue (contrat « meilleur effort » de l'import
     * d'étudiants) : le secrétariat colle son tableau, le rapport dit quelles
     * lignes sont passées et pourquoi les autres non.
     *
     * <p>VOLONTAIREMENT sans transaction englobante : chaque {@code save}
     * commet indépendamment, une ligne en erreur ne doit pas annuler les
     * lignes déjà créées.
     */
    public MatiereImportResult importer(List<MatiereImportRow> rows,
                                        Long acteurId, String acteurEmail) {
        List<MatiereImportResult.Row> verdicts = new ArrayList<>();
        Set<String> codesVus = new HashSet<>();
        int crees = 0;
        int doublons = 0;
        int erreurs = 0;

        for (int i = 0; i < rows.size(); i++) {
            int ligne = i + 1;
            MatiereImportRow row = rows.get(i);
            String code = row.code() == null ? "" : row.code().trim();
            String libelle = row.libelle() == null ? "" : row.libelle().trim();

            String invalide = validerLigne(code, libelle);
            if (invalide != null) {
                verdicts.add(new MatiereImportResult.Row(
                        ligne, code, MatiereImportResult.Statut.ERROR, invalide));
                erreurs++;
                continue;
            }
            if (!codesVus.add(code.toLowerCase())) {
                verdicts.add(new MatiereImportResult.Row(
                        ligne, code, MatiereImportResult.Statut.DUPLICATE,
                        "Code en double dans l'envoi lui-même."));
                doublons++;
                continue;
            }
            if (matiereRepository.findByCodeIgnoreCase(code).isPresent()) {
                verdicts.add(new MatiereImportResult.Row(
                        ligne, code, MatiereImportResult.Statut.DUPLICATE,
                        "Ce code existe déjà au catalogue."));
                doublons++;
                continue;
            }

            try {
                matiereRepository.save(Matiere.builder().code(code).libelle(libelle).build());
                verdicts.add(new MatiereImportResult.Row(
                        ligne, code, MatiereImportResult.Statut.CREATED, "Créée."));
                crees++;
            } catch (DataIntegrityViolationException e) {
                // Course sur l'index unique (créée entre le contrôle et l'insert).
                verdicts.add(new MatiereImportResult.Row(
                        ligne, code, MatiereImportResult.Statut.DUPLICATE,
                        "Ce code existe déjà au catalogue."));
                doublons++;
            }
        }

        auditService.logAttribue(null, acteurEmail, AuditAction.MATIERE_IMPORTED,
                rows.size() + " lignes : " + crees + " créées, "
                        + doublons + " doublons, " + erreurs + " erreurs",
                null, acteurId);
        return new MatiereImportResult(crees, doublons, erreurs, verdicts);
    }

    /** Mêmes règles que {@link MatiereRequest}, appliquées ligne par ligne. */
    private String validerLigne(String code, String libelle) {
        if (code.isBlank()) {
            return "Le code est obligatoire.";
        }
        if (code.length() > 20) {
            return "Le code ne peut pas dépasser 20 caractères.";
        }
        if (libelle.isBlank()) {
            return "Le libellé est obligatoire.";
        }
        if (libelle.length() > 100) {
            return "Le libellé ne peut pas dépasser 100 caractères.";
        }
        return null;
    }

    // -------------------------------------------------------------------------

    private Matiere findOrThrow(Long id) {
        return matiereRepository.findById(id)
                .orElseThrow(() -> new MatiereNotFoundException("Matière introuvable : " + id));
    }

    /** Unicité du code, sans la casse (#285), en excluant la matière elle-même au renommage. */
    private void verifierCodeDisponible(String code, Long selfId) {
        matiereRepository.findByCodeIgnoreCase(code)
                .filter(existante -> !existante.getId().equals(selfId))
                .ifPresent(existante -> {
                    throw new MatiereConflictException(
                            "Le code « " + code + " » est déjà pris par la matière « "
                                    + existante.getLibelle() + " »"
                                    + (Boolean.FALSE.equals(existante.getActive())
                                            ? " (retirée — rouvrez-la plutôt que de la recréer)."
                                            : "."));
                });
    }
}
