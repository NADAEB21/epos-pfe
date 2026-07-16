package tn.epos.scoring_service.dto;

/**
 * Summary returned when an exam's generated rotation plan is wiped as part of a
 * reset (#183 — « dé-lancer »). Lets the frontend confirm exactly what was
 * removed without re-querying. Only the <b>generated schedule</b> is affected:
 * lots, roster and présence are preserved.
 */
public record ResetRotationsResult(
        int lots,
        int groupes) {
}
