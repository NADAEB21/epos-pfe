package tn.epos.scoring_service.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.dashboard.EvaluateurDashboardResponse;
import tn.epos.scoring_service.dto.dashboard.LotDetailResponse;
import tn.epos.scoring_service.dto.dashboard.SaisirNotationRequest;
import tn.epos.scoring_service.dto.dashboard.ValiderEtudiantRequest;
import tn.epos.scoring_service.service.EvaluateurDashboardService;

/**
 * Endpoints d'agrégation dédiés à l'app mobile Flutter (évaluateur).
 *
 * Ces endpoints correspondent exactement aux 3 appels du SessionRepository Flutter :
 *   - GET  /api/evaluateur/dashboard                         → getSessions() + getStats() + getPlanningDuJour()
 *   - GET  /api/evaluateur/stations/{stationId}/lots/{n}     → GradingRepository.getLot()
 *   - POST /api/evaluateur/notations/saisir                  → GradingRepository.saveNotation()
 *   - POST /api/evaluateur/etudiants/{id}/stations/{id}/valider → GradingRepository.validerEtudiant()
 *   - POST /api/evaluateur/lots/{id}/valider                 → GradingRepository.validerLot()
 *
 * Sécurité : accessible aux trois rôles (EVALUATEUR en production,
 * SUPER_ADMIN et RESPONSABLE_MATIERE pour les tests et la supervision).
 *
 * L'userId est extrait directement du JWT signé (claim "userId")
 * pour éviter toute usurpation via paramètre de requête.
 */
@RestController
@RequestMapping("/api/evaluateur")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('EVALUATEUR', 'SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
public class EvaluateurDashboardController {

    private final EvaluateurDashboardService dashboardService;

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/evaluateur/dashboard
    //
    // Retourne tout ce dont l'HomeScreen Flutter a besoin en un seul appel :
    //   - sessions  → SessionBloc (SessionLoaded.sessions)
    //   - stats     → SessionBloc (SessionLoaded.stats)
    //   - planning  → SessionBloc (SessionLoaded.planning)
    //
    // Remplace les 3 appels mock dans session_repository_impl.dart :
    //   getSessions(), getStats(), getPlanningDuJour()
    // ──────────────────────────────────────────────────────────────────────────
    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<EvaluateurDashboardResponse>> getDashboard(
            @AuthenticationPrincipal Jwt jwt) {

        Long evaluateurId = extractUserId(jwt);
        EvaluateurDashboardResponse response = dashboardService.buildDashboard(evaluateurId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/evaluateur/stations/{stationId}/lots/{lotNumero}
    //
    // Retourne le lot courant avec la liste complète des étudiants.
    // Correspond à GradingRepository.getLot(stationId, lotNumero) Flutter.
    // ──────────────────────────────────────────────────────────────────────────
    @GetMapping("/rotations/{rotationId}/groupe")
    public ResponseEntity<ApiResponse<LotDetailResponse>> getGroupeDetail(
            @PathVariable Long rotationId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(
                dashboardService.getGroupeDetail(rotationId, extractUserId(jwt))));
    }

    /**
     * #209 — « Groupe suivant » est un ACTE, donc un POST : il ouvre le rang suivant de la
     * station (EN_COURS + horodatage {@code debutReel}) et le renvoie. Découplé de
     * {@code /valider}, qui ne fait plus que verrouiller — seul CE clic avance.
     */
    @PostMapping("/rotations/{rotationId}/suivant")
    public ResponseEntity<ApiResponse<LotDetailResponse>> avancerGroupe(
            @PathVariable Long rotationId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(
                dashboardService.avancerGroupe(rotationId, extractUserId(jwt))));
    }

    // Remplace l'appel Flutter cassé vers /rotations/{lotId}/valider
    @PostMapping("/rotations/{rotationId}/valider")
    public ResponseEntity<ApiResponse<Void>> validerGroupe(
            @PathVariable Long rotationId, @AuthenticationPrincipal Jwt jwt) {
        dashboardService.validerGroupe(rotationId, extractUserId(jwt));
        return ResponseEntity.ok(ApiResponse.ok("Groupe validé pour cette station"));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // POST /api/evaluateur/notations/saisir
    //
    // Sauvegarde la notation d'un étudiant pour un critère donné.
    // L'app Flutter envoie { etudiantId, stationId, grilleId, itemId, valeur }.
    // Le backend retrouve automatiquement le RotationAssignment et le NotationItem.
    //
    // Correspond à GradingRepository.saveNotation(notation) Flutter.
    // ──────────────────────────────────────────────────────────────────────────
    @PostMapping("/notations/saisir")
    public ResponseEntity<ApiResponse<Void>> saisirNotation(
            @RequestBody SaisirNotationRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        Long evaluateurId = extractUserId(jwt);
        dashboardService.saisirNotation(request, evaluateurId);
        return ResponseEntity.ok(ApiResponse.ok("Notation enregistrée"));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // POST /api/evaluateur/etudiants/{etudiantId}/stations/{stationId}/valider
    //
    // Verrouille toutes les notes d'un étudiant pour une station.
    // Correspond à GradingRepository.validerEtudiant(etudiantId, stationId) Flutter.
    // ──────────────────────────────────────────────────────────────────────────
    @PostMapping("/etudiants/{etudiantId}/stations/{stationId}/valider")
    public ResponseEntity<ApiResponse<Void>> validerEtudiant(
            @PathVariable Long etudiantId,
            @PathVariable Long stationId,
            @Valid @RequestBody ValiderEtudiantRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        Long evaluateurId = extractUserId(jwt);
        dashboardService.validerEtudiant(etudiantId, stationId, evaluateurId, request);
        return ResponseEntity.ok(
                ApiResponse.ok("Notes verrouillées pour l'étudiant " + etudiantId));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // POST /api/evaluateur/lots/{lotId}/valider
    //
    // Marque le lot comme TERMINE (tous les étudiants validés).
    // Correspond à GradingRepository.validerLot(lotId) Flutter.
    // ──────────────────────────────────────────────────────────────────────────
    @PostMapping("/lots/{lotId}/valider")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Void>> validerLot(
            @PathVariable Long lotId,
            @AuthenticationPrincipal Jwt jwt) {

        Long evaluateurId = extractUserId(jwt);
        dashboardService.validerLot(lotId, evaluateurId);
        return ResponseEntity.ok(ApiResponse.ok("Lot " + lotId + " validé"));
    }

    // ── Utilitaire ───────────────────────────────────────────────────────────

    /**
     * Extrait l'userId depuis le claim JWT de manière sûre.
     *
     * Le claim "userId" peut être désérialisé en Integer ou Long selon la
     * taille de la valeur et le parser JSON utilisé par Nimbus. On passe
     * toujours par Number pour éviter un ClassCastException au runtime.
     */
    private Long extractUserId(Jwt jwt) {
        Object claim = jwt.getClaim("userId");
        if (claim instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException(
                "Le claim 'userId' est absent ou invalide dans le JWT. " +
                        "Vérifiez que auth-service inclut bien ce claim lors de la génération du token.");
    }
}