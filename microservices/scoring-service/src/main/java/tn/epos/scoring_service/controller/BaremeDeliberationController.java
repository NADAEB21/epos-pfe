package tn.epos.scoring_service.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.BaremeDeliberationDTO;
import tn.epos.scoring_service.dto.BaremeDeliberationRequest;
import tn.epos.scoring_service.service.BaremeDeliberationService;

import java.util.List;

/**
 * ADR-0030 (issue #361) — barème de délibération, l'acte du RESPONSABLE
 * (jamais de l'IA : ai-service n'a aucun chemin d'écriture vers scoring,
 * ADR-0029 D2 ; une proposition ne devient un barème que par cette porte-ci).
 *
 * <p>Monté sous {@code /api/notations/examen/{id}/…} et non l'écriture
 * littérale d'ADR-0030 D1 ({@code POST /examens/{id}/…}) : la gateway route
 * {@code /api/v1/examens/**} vers exam-service — même arbitrage, documenté au
 * même endroit, que {@code /examen/{id}/results} (#90) et
 * {@code /examen/{id}/grilles} (#355), les deux voisins de ce même écran.
 *
 * <p>Contrôleur SÉPARÉ de {@link NotationController} à dessein : lui ajouter un
 * collaborateur casserait le chargement de contexte des trois classes
 * {@code @WebMvcTest} existantes qui le montent.
 */
@RestController
@RequestMapping("/api/notations/examen/{examenId}/bareme-deliberation")
@RequiredArgsConstructor
public class BaremeDeliberationController {

    private final BaremeDeliberationService service;

    // Rôle ici, MATIÈRE dans le service (#274), examen CLOS dans le service
    // (statut lu en strict — fail-closed). motif obligatoire (@Valid).
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<BaremeDeliberationDTO>> creer(
            @PathVariable Long examenId,
            @Valid @RequestBody BaremeDeliberationRequest request) {
        BaremeDeliberationDTO dto = service.creer(examenId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Barème de délibération v" + dto.version() + " enregistré",
                        dto));
    }

    // Historique COMPLET, version la plus récente d'abord — qui, quand, quel
    // motif, quelles opérations (ADR-0030 D3/D4 : tout reste lisible).
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<List<BaremeDeliberationDTO>>> historique(
            @PathVariable Long examenId) {
        return ResponseEntity.ok(ApiResponse.ok(service.historique(examenId)));
    }
}
