package tn.epos.scoring_service.dto;

import java.util.List;

/** #435 — retrait groupé : les participations (inscriptions) à retirer d'un examen. */
public record BulkRetraitRequest(List<Long> participationIds) {}
