package tn.epos.scoring_service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tn.epos.scoring_service.entities.TypeOperationBareme;

import java.util.List;

/**
 * Body de {@code POST /api/notations/examen/{examenId}/bareme-deliberation}
 * (ADR-0030 D1/D2, issue #361).
 *
 * <p>{@code motif} est obligatoire : l'acte de délibération se justifie
 * toujours. {@code operations} peut être VIDE — c'est la version « retour au
 * barème du lancement » (D3), motivée elle aussi. Chaque opération porte
 * EXACTEMENT une cible : {@code cibleItemId} (EXCLURE_CRITERE, REPONDERER
 * critère) ou {@code cibleStationId} (EXCLURE_STATION, REPONDERER station) —
 * des ids du SNAPSHOT, validés en service contre {@code exam_item_snapshot} /
 * {@code exam_grille_snapshot}. La cohérence type↔cible et {@code
 * nouvelleEchelle} (exigée par REPONDERER seul) est vérifiée en service, avec
 * des refus nominatifs — pas exprimable en annotations champ à champ.
 */
public record BaremeDeliberationRequest(
        @NotBlank(message = "motif est obligatoire (justification de la délibération)")
        String motif,

        @NotNull(message = "operations est obligatoire (liste vide = retour au barème d'origine)")
        List<@Valid OperationRequest> operations
) {

    public record OperationRequest(
            @NotNull(message = "type est obligatoire (EXCLURE_CRITERE, EXCLURE_STATION ou REPONDERER)")
            TypeOperationBareme type,

            Long cibleItemId,

            Long cibleStationId,

            @Positive(message = "nouvelleEchelle doit être strictement positive")
            Double nouvelleEchelle
    ) {
    }
}
