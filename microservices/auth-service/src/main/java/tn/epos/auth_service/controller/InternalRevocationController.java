package tn.epos.auth_service.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.epos.auth_service.service.TokenRevocationService;
import tn.epos.common.dto.ApiResponse;

import java.util.List;

/**
 * #306 — la liste de révocation, servie aux autres services (gateway, exam, scoring), qui la
 * rapatrient périodiquement via {@code RevocationSyncClient}.
 *
 * <p><b>Pas un endpoint public.</b> {@code /internal/**} n'est pas dans l'allowlist de routes
 * de la gateway (donc injoignable depuis l'hôte, qui n'expose que la gateway), et
 * {@link tn.epos.auth_service.config.InternalAuthFilter} exige la preuve dérivée de
 * {@code JWT_SECRET} — défense en profondeur pour le jour où l'un de ces deux faits change.
 *
 * <p>Le corps ne transporte que des époques en millisecondes : un {@code LocalDateTime} sans
 * zone envoyé à un autre conteneur est la famille de bug déjà payée sur le verrou temporaire
 * (session 34) — le client le relirait dans SA zone.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalRevocationController {

    private final TokenRevocationService tokenRevocationService;

    @GetMapping("/revocations")
    public ResponseEntity<ApiResponse<List<TokenRevocationService.RevocationEntry>>> revocations() {
        return ResponseEntity.ok(ApiResponse.ok(tokenRevocationService.recentRevocations()));
    }
}
