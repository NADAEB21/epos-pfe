package tn.epos.scoring_service.dto;

import java.util.List;

/**
 * Summary of a bulk student import. The four counters bucket every row by its
 * {@link ImportRowResult#statut()} and sum to {@code total}, so the frontend can
 * show a headline ("3 nouveaux, 2 inscrits, 1 déjà inscrit, 0 erreur") above the
 * per-row table.
 */
public record ImportResult(
    int total,
    int created,
    int enrolled,
    int alreadyEnrolled,
    int errors,
    List<ImportRowResult> rows
) {}
