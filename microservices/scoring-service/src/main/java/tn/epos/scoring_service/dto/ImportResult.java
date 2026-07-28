package tn.epos.scoring_service.dto;

import java.util.List;

/**
 * Summary of a bulk student import. The four status counters bucket every row by
 * its {@link ImportRowResult#statut()} and sum to {@code total}, so the frontend
 * can show a headline ("3 nouveaux, 2 inscrits, 1 déjà inscrit, 0 erreur") above
 * the per-row table.
 *
 * <p>{@code emailsRenseignes} sits OUTSIDE that partition on purpose and does not
 * sum into {@code total}. Re-importing the roster with the missing addresses
 * filled in is a legitimate, useful action, and it lands every row on
 * {@code ALREADY_ENROLLED} — so the four counters read
 * "0 created, 0 enrolled, 10 already enrolled", i.e. exactly like a no-op, while
 * real work happened. #227: without this counter the UI cannot tell the teacher
 * that the thing they came to do actually succeeded.
 */
public record ImportResult(
    int total,
    int created,
    int enrolled,
    int alreadyEnrolled,
    int errors,
    int emailsRenseignes,
    List<ImportRowResult> rows
) {}
