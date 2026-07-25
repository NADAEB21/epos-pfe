package tn.epos.scoring_service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.epos.common.dto.ApiResponse;
import tn.epos.scoring_service.dto.ChangerJourRequest;
import tn.epos.scoring_service.dto.LotDTO; // New Import
import tn.epos.scoring_service.dto.ParticipationDTO;
import tn.epos.scoring_service.dto.dashboard.SuiviProgressionResponse;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.service.LotAssignmentService;
import tn.epos.scoring_service.service.LotOuvertureService;
import tn.epos.scoring_service.service.LotService;
import tn.epos.scoring_service.service.SuiviProgressionService;

import java.util.List;

@RestController
@RequestMapping("/api/lots")
public class LotController {

    private static final String NOT_FOUND_MSG = "Lot non trouvé";

    @Autowired
    private LotService lotService;

    @Autowired
    private LotAssignmentService lotAssignmentService;

    @Autowired
    private LotOuvertureService lotOuvertureService;

    @Autowired
    private SuiviProgressionService suiviProgressionService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<List<LotDTO>>> getAllLots(
            @RequestParam(required = false) Long examenId) {
        List<Lot> lots = (examenId != null)
                ? lotService.findByExamenId(examenId)
                : lotService.findAll();
        List<LotDTO> dtos = lots.stream()
                .map(LotDTO::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(dtos));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE', 'EVALUATEUR')")
    public ResponseEntity<ApiResponse<LotDTO>> getLotById(@PathVariable Long id) {
        return lotService.findById(id)
                .map(lot -> ResponseEntity.ok(ApiResponse.ok(LotDTO.fromEntity(lot))))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(NOT_FOUND_MSG)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<LotDTO>> createLot(@RequestBody LotDTO dto) {
        Lot entity = new Lot();
        entity.setNumeroLot(dto.numeroLot());
        entity.setTailleLot(dto.tailleLot());
        entity.setStatut(dto.statut());
        entity.setEvaluateurId(dto.evaluateurId());
        entity.setExamenId(dto.examenId());
        entity.setJour(dto.jour()); // #147 — null = jour unique de l'examen
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Lot créé avec succès", LotDTO.fromEntity(lotService.save(entity))));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<LotDTO>> updateLot(@PathVariable Long id, @RequestBody LotDTO dto) {
        Lot entity = new Lot();
        entity.setNumeroLot(dto.numeroLot());
        entity.setTailleLot(dto.tailleLot());
        entity.setStatut(dto.statut());
        entity.setJour(dto.jour()); // #147 — appliqué seulement si non-null (cf. LotService.update)
        // Pass to service update logic
        return ResponseEntity.ok(ApiResponse.ok("Lot mis à jour", LotDTO.fromEntity(lotService.update(id, entity))));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Void>> deleteLot(@PathVariable Long id) {
        lotService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Lot supprimé"));
    }

    /**
     * ADR-0014-B — ouvre une vague : « Lot suivant » du responsable (#207/#208).
     *
     * <p>Réservé à RESP/ADMIN, et <b>délibérément fermé à EVALUATEUR</b> : un lot est servi
     * simultanément par tous les évaluateurs, donc l'ouvrir engage la salle entière et les
     * stations de tous les collègues. L'évaluateur, lui, avance ses propres GROUPES
     * (« Groupe suivant », {@code POST /api/evaluateur/rotations/{id}/valider-groupe}).
     *
     * <p>Refuse — bruyamment — tant que la vague précédente n'est pas terminée, ce qui se
     * mesure sur l'état des rotations (tous les évaluateurs ont validé), jamais sur une durée.
     * 404 lot inconnu ; 400 présence non prise, planning non généré, lot déjà ouvert, ou
     * vague précédente inachevée.
     */
    @PostMapping("/{id}/ouvrir")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<Void>> ouvrirLot(@PathVariable Long id) {
        lotOuvertureService.ouvrirLot(id);
        return ResponseEntity.ok(ApiResponse.ok("Lot ouvert : les étudiants peuvent être évalués."));
    }

    /**
     * #208 / #252 — tableau de progression du responsable pendant l'épreuve.
     *
     * <p>Renvoie, DÉRIVÉ de l'état stocké : la vague ouverte et son instant d'ouverture réel
     * ({@code ouvertA}), l'alerte « lot terminé », le lot que « Lot suivant » ouvrira, et par
     * station le groupe en cours + « N/M notés ».
     *
     * <p>Le client ne recalcule rien : c'est en laissant le web re-déduire un statut depuis
     * l'horloge que le Suivi affichait « dépassement / encore en cours » sur des rotations
     * pourtant TERMINE en base.
     */
    @GetMapping("/examens/{examenId}/progression")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<SuiviProgressionResponse>> progression(@PathVariable Long examenId) {
        return ResponseEntity.ok(ApiResponse.ok(suiviProgressionService.getProgression(examenId)));
    }

    /**
     * Manually move one enrolled student into this lot (#165) — RESP/ADMIN,
     * CONFIGURE-only. Reads as "put participation {participationId} into lot
     * {targetLotId}", the single-student alternative to the wipe-and-redo
     * {@code POST /examens/{id}/repartir}. Returns the updated participation
     * (now carrying the new lotId). 404 unknown lot/participation; 400 when the
     * target lot is in another exam or the exam is no longer CONFIGURE.
     */
    @PatchMapping("/{targetLotId}/etudiants/{participationId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<ParticipationDTO>> deplacerEtudiant(
            @PathVariable Long targetLotId, @PathVariable Long participationId) {
        ParticipationDTO dto = lotAssignmentService.deplacerEtudiant(targetLotId, participationId);
        return ResponseEntity.ok(ApiResponse.ok("Étudiant déplacé", dto));
    }

    /**
     * #147 / ADR-0014-A §5 — fixe (ou efface : {@code {"jour": null}}) le jour de
     * passage d'un lot, pour étaler une cohorte sur plusieurs jours. RESP/ADMIN,
     * CONFIGURE-only (même fenêtre que la répartition). Le PUT générique ne
     * convient pas : sa sémantique PATCH (#215) ignore un {@code jour} null, donc
     * ne sait pas RE-attacher un lot au jour unique de l'examen.
     */
    @PatchMapping("/{id}/jour")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'RESPONSABLE_MATIERE')")
    public ResponseEntity<ApiResponse<LotDTO>> changerJour(
            @PathVariable Long id, @RequestBody ChangerJourRequest body) {
        LotDTO dto = lotAssignmentService.changerJour(id, body.jour());
        return ResponseEntity.ok(ApiResponse.ok(
                body.jour() == null ? "Lot rattaché au jour de l'examen" : "Jour du lot mis à jour", dto));
    }
}