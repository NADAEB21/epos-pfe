package tn.epos.scoring_service.dto;

import java.util.List;

/** Corps de POST /api/participations/bulk — #186. */
public record BulkEnrolRequest(List<Long> etudiantIds) {}