package tn.epos.scoring_service.dto;

import java.util.List;

/**
 * Body for the bulk per-lot presence call (Phase 2 — exam day). Default posture
 * is "the whole wave showed up": every participation in the lot is marked present
 * except the ones listed in {@code absents}. A null/empty list means all present.
 */
public record PresenceBulkRequest(List<Long> absents) {
}
