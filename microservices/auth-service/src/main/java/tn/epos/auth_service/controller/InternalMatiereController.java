package tn.epos.auth_service.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.epos.auth_service.repository.MatiereRepository;
import tn.epos.common.dto.ApiResponse;

import java.util.List;

/**
 * #303 — les matières RETIRÉES du catalogue, servies à exam-service qui les rapatrie
 * périodiquement (même canal, même posture et même garde que {@code /internal/revocations},
 * #306) : {@code permitAll} côté chaîne de sécurité, mais {@code InternalAuthFilter} exige la
 * preuve HMAC dérivée de {@code JWT_SECRET}, et {@code /internal/**} n'est pas routé par la
 * gateway — injoignable depuis l'hôte.
 *
 * <p>Le libellé accompagne l'id pour que le refus d'exam-service soit NOMINATIF
 * (« Pharmacognosie », pas « matière 10 ») — exam-service n'a aucune copie du catalogue.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalMatiereController {

    private final MatiereRepository matiereRepository;

    public record RetiredMatiereEntry(Long id, String libelle) {}

    @GetMapping("/matieres-retirees")
    public ResponseEntity<ApiResponse<List<RetiredMatiereEntry>>> matieresRetirees() {
        List<RetiredMatiereEntry> retirees = matiereRepository.findByActiveFalse().stream()
                .map(m -> new RetiredMatiereEntry(m.getId(), m.getLibelle()))
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(retirees));
    }
}
