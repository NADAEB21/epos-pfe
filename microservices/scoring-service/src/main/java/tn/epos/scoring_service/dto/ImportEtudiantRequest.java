package tn.epos.scoring_service.dto;

/**
 * One row of a bulk student import (POST /etudiants/import). The frontend parses
 * the CSV / .xlsx (SheetJS) into these normalized rows; field names mirror the
 * single-row {@link EtudiantDTO} verbatim (snake_case numero_inscription). The
 * backend find-or-creates the {@link tn.epos.scoring_service.entities.Etudiant}
 * by numero_inscription, then enrols it on the target exam.
 */
public record ImportEtudiantRequest(
    String nom,
    String prenom,
    String numero_inscription
) {}
