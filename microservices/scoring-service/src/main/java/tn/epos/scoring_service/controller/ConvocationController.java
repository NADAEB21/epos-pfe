package tn.epos.scoring_service.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.ConvocationDTO;
import tn.epos.scoring_service.dto.EnvoiConvocationsResult;
import tn.epos.scoring_service.service.ConvocationService;

import java.util.List;

/**
 * #227 — les convocations d'un examen : ce que chaque étudiant doit savoir
 * (son lot, son jour, son heure d'arrivée) et leur envoi par e-mail.
 *
 * <p>Réservé au RESPONSABLE_MATIERE et au SUPER_ADMIN : convoquer une promotion
 * est un acte d'organisation, pas une lecture. L'ÉVALUATEUR n'a rien à y faire —
 * il découvre ses groupes le jour J.
 */
@RestController
@RequestMapping("/api/convocations")
public class ConvocationController {

    private final ConvocationService convocationService;

    public ConvocationController(ConvocationService convocationService) {
        this.convocationService = convocationService;
    }

    /**
     * Les convocations, dans l'ordre officiel du listing (#256). Le web LIT
     * cette dérivation au lieu de recalculer l'heure d'arrivée de son côté :
     * une seule règle, donc l'écran et l'e-mail de l'étudiant ne peuvent pas
     * se contredire.
     */
    @GetMapping("/examens/{examenId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<List<ConvocationDTO>>> lister(@PathVariable Long examenId) {
        List<ConvocationDTO> convocations = convocationService.construire(examenId);
        return ResponseEntity.ok(ApiResponse.ok("Convocations", convocations));
    }

    /**
     * Envoie la convocation à chaque étudiant joignable.
     *
     * <p>Renvoie toujours 200 avec un bilan détaillé, même si certains envois
     * ont échoué : un 500 global ferait disparaître l'information « ces 18-là
     * sont bien partis, ces 2-là non ». Le bilan porte aussi {@code simule},
     * pour que l'écran n'annonce jamais « envoyé » quand la messagerie est
     * désactivée (l'état par défaut).
     */
    @PostMapping("/examens/{examenId}/envoyer")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<EnvoiConvocationsResult>> envoyer(@PathVariable Long examenId) {
        EnvoiConvocationsResult result = convocationService.envoyer(examenId);
        String message = result.simule()
                ? "Envoi simulé (messagerie désactivée) — aucun e-mail n'est parti."
                : result.envoyes() + " convocation(s) envoyée(s).";
        return ResponseEntity.ok(ApiResponse.ok(message, result));
    }
}
