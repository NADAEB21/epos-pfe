package tn.epos.scoring_service.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.RemplacerEvaluateurRequest;
import tn.epos.scoring_service.dto.SubstitutionResult;
import tn.epos.scoring_service.service.EvaluateurSubstitutionService;

/**
 * ADR-0017 — suppléance d'un évaluateur en cours d'épreuve.
 *
 * <p><b>Responsable / admin uniquement.</b> Un évaluateur ne cède pas sa station
 * et ne réclame pas celle d'un autre : staffer une station est un acte
 * d'organisation, pas une décision de celui qui la tient (même argument
 * qu'ADR-0014-B pour l'avancement des lots).
 *
 * <p>Avant la vague, il n'y a rien à faire ici : on réaffecte la station côté
 * exam-service et on démarre le lot (ADR-0017 §2). Ce point de terminaison ne
 * sert qu'au départ EN COURS d'examen.
 */
@RestController
@RequestMapping("/api/lots")
@RequiredArgsConstructor
public class EvaluateurSubstitutionController {

    private final EvaluateurSubstitutionService substitutionService;

    @PostMapping("/{lotId}/stations/{stationId}/remplacer-evaluateur")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<SubstitutionResult>> remplacer(
            @PathVariable Long lotId,
            @PathVariable Long stationId,
            @Valid @RequestBody RemplacerEvaluateurRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        SubstitutionResult result = substitutionService.remplacer(
                lotId, stationId, request.nouvelEvaluateurId(), request.motif(), extractUserId(jwt));
        return ResponseEntity.ok(ApiResponse.ok(result.message(), result));
    }

    private Long extractUserId(Jwt jwt) {
        Object claim = jwt.getClaim("userId");
        if (claim instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Le claim 'userId' est absent ou invalide dans le JWT.");
    }
}
