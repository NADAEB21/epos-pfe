package tn.epos.auth_service.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.epos.common.dto.ApiResponse;
import tn.epos.auth_service.config.JwtAuthenticationDetails;
import tn.epos.auth_service.dto.MatiereImportResult;
import tn.epos.auth_service.dto.MatiereImportRow;
import tn.epos.auth_service.dto.MatiereRequest;
import tn.epos.auth_service.dto.MatiereResponse;
import tn.epos.auth_service.dto.MotifRequest;
import tn.epos.auth_service.service.MatiereService;

import java.util.List;

/**
 * #134 — le catalogue des matières, domaine d'écriture légitime du
 * SUPER_ADMIN (ADR-0018 D5). Aucun DELETE : le retrait est l'acte de
 * fermeture, motivé et réversible (doctrine #289 appliquée au catalogue).
 */
@RestController
@RequestMapping("/api/v1/matieres")
@RequiredArgsConstructor
public class MatiereController {

    private final MatiereService matiereService;

    /**
     * GET /api/v1/matieres
     * Lists all pharmacy subjects — retired ones included, flagged
     * {@code active=false}. Used by frontend pickers (exam creation, role
     * assignment — they exclude retired) and by label lookups on historical
     * data (they must NOT exclude retired). Any authenticated user.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<MatiereResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(matiereService.list()));
    }

    /** POST /api/v1/matieres — créer une matière. SUPER_ADMIN seul. */
    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<MatiereResponse>> creer(
            @Valid @RequestBody MatiereRequest request,
            Authentication authentication) {
        MatiereResponse created = matiereService.creer(
                request, acteurId(authentication), acteurEmail(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    /**
     * PUT /api/v1/matieres/{id} — renommer (code et/ou libellé). SUPER_ADMIN
     * seul. Les références restent intactes : tout pointe sur l'id.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<MatiereResponse>> modifier(
            @PathVariable Long id,
            @Valid @RequestBody MatiereRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.ok(matiereService.modifier(
                id, request, acteurId(authentication), acteurEmail(authentication))));
    }

    /**
     * POST /api/v1/matieres/{id}/retrait — retirer du catalogue actif.
     * SUPER_ADMIN seul. Pas de DELETE : les examens passés référencent la
     * matière en clé logique inter-services (ADR-0006) et leurs résultats
     * doivent garder son nom. Motif OBLIGATOIRE, auteur enregistré, réversible.
     */
    @PostMapping("/{id}/retrait")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<MatiereResponse>> retirer(
            @PathVariable Long id,
            @Valid @RequestBody MotifRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.ok(matiereService.retirer(
                id, request.motif(), acteurId(authentication), acteurEmail(authentication))));
    }

    /** POST /api/v1/matieres/{id}/reactivation — rouvrir une matière retirée. */
    @PostMapping("/{id}/reactivation")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<MatiereResponse>> reactiver(
            @PathVariable Long id,
            @Valid @RequestBody MotifRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.ok(matiereService.reactiver(
                id, request.motif(), acteurId(authentication), acteurEmail(authentication))));
    }

    /**
     * POST /api/v1/matieres/import — import en lot, verdict par ligne.
     * SUPER_ADMIN seul. Meilleur effort : les lignes valides passent même si
     * d'autres échouent ; le rapport dit quoi et pourquoi, ligne par ligne.
     */
    @PostMapping("/import")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<MatiereImportResult>> importer(
            @RequestBody List<MatiereImportRow> rows,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.ok(matiereService.importer(
                rows, acteurId(authentication), acteurEmail(authentication))));
    }

    /** L'id porté par le JWT — l'auteur de l'acte. Même helper que UserController. */
    private Long acteurId(Authentication authentication) {
        if (authentication != null
                && authentication.getDetails() instanceof JwtAuthenticationDetails details) {
            return details.getUserId();
        }
        return null;
    }

    /** L'e-mail de l'auteur (sub du JWT) — audit_logs.email est NOT NULL. */
    private String acteurEmail(Authentication authentication) {
        return authentication == null ? "?" : authentication.getName();
    }
}
