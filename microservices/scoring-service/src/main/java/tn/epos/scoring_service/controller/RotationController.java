package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.RotationDTO;
import tn.epos.scoring_service.repositories.IRotationRepository;
import tn.epos.scoring_service.service.RotationService;

import java.util.List;

@RestController
@RequestMapping("/api/rotations")
public class RotationController {

    private static final String NOT_FOUND_MSG = "Rotation non trouvée";

    @Autowired private RotationService          rotationService;
    @Autowired private IRotationRepository      rotationRepository;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<RotationDTO>>> getAll() {
        List<RotationDTO> dtos = rotationService.findAll().stream()
                .map(RotationDTO::fromEntity).toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<RotationDTO>> getById(@PathVariable Long id) {
        return rotationService.findById(id)
                .map(r -> ResponseEntity.ok(ApiResponse.ok(RotationDTO.fromEntity(r))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(NOT_FOUND_MSG)));
    }

    @GetMapping("/group/{groupId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<RotationDTO>>> getByGroup(@PathVariable Long groupId) {
        List<RotationDTO> dtos = rotationService.findByGroup(groupId).stream()
                .map(RotationDTO::fromEntity).toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @GetMapping("/station/{stationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<RotationDTO>>> getByStation(@PathVariable Long stationId) {
        List<RotationDTO> dtos = rotationService.findByStation(stationId).stream()
                .map(RotationDTO::fromEntity).toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    /**
     * Combien de rotations existent déjà pour ce lot (issue #188).
     *
     * <p>Permet au front de savoir, dès le chargement, qu'un lot est DÉJÀ généré — donc que
     * le bouton doit lire « Régénérer » et demander confirmation avant une action destructrice.
     * L'état de session ne survit pas à un reload ; celui-ci vient du serveur.
     */
    @GetMapping("/lot/{lotId}/count")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Long>> countByLot(@PathVariable Long lotId) {
        return ResponseEntity.ok(ApiResponse.ok(rotationRepository.countByStudentGroupLotId(lotId)));
    }

    // =========================================================================
    // POST / PUT / DELETE /api/rotations — SUPPRIMES (#86, #219). Ne pas reintroduire.
    //
    // Une rotation est de l'ETAT DERIVE : le circuit est construit par
    // RotationGenerationService (carre latin) a partir des presents. Ces trois portes
    // laissaient le rediger a la main, sans aucune garde, et `PUT` laissait meme ecrire
    // `statut` directement — le maquillage qu'ADR-0014 §4 interdit. Zero appelant dans
    // les deux clients. Voir le bloc de suppression dans RotationService pour le detail
    // et la mesure en direct.
    //
    // Le seul chemin d'ecriture legitime, deja borne a la matiere (#274) :
    //   POST /api/rotations/lots/{lotId}/generer
    //   POST /api/rotations/examens/{examenId}/reset
    // =========================================================================
}