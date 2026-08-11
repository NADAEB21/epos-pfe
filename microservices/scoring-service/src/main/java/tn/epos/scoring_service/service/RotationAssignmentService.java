package tn.epos.scoring_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tn.epos.scoring_service.config.EvaluateurScopeChecker;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationAssignment;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;
import tn.epos.scoring_service.repositories.IRotationAssignmentRepository;
import tn.epos.scoring_service.repositories.IRotationRepository;

import java.util.List;
import java.util.Optional;

@Service
public class RotationAssignmentService {

    @Autowired
    private IRotationAssignmentRepository repository;

    @Autowired
    private IRotationRepository rotationRepository;

    @Autowired
    private IExamenParticipationRepository participationRepository;

    @Autowired
    private EvaluateurScopeChecker scopeChecker;

    // Récupérer toutes les assignments — filtrées au périmètre de l'évaluateur (#91)
    public List<RotationAssignment> findAll() {
        return scoped(repository.findAll());
    }

    // Récupérer par ID
    public Optional<RotationAssignment> findById(Long id) {
        return repository.findById(id);
    }

    // Récupérer par rotation — filtrées (#91)
    public List<RotationAssignment> findByRotation(Long rotationId) {
        return scoped(repository.findByRotationId(rotationId));
    }

    // Filtre une liste d'assignments au périmètre de l'évaluateur appelant via
    // RotationAssignment -> Rotation.evaluateurId. Un appelant non contraint
    // (SUPER_ADMIN / RESPONSABLE_MATIERE) voit tout.
    private List<RotationAssignment> scoped(List<RotationAssignment> assignments) {
        if (scopeChecker.peutLireHorsPerimetre()) {
            return assignments;
        }
        return assignments.stream()
                .filter(a -> scopeChecker.isCaller(
                        a.getRotation() != null ? a.getRotation().getEvaluateurId() : null))
                .toList();
    }

    // =========================================================================
    // ÉCRITURE BRUTE SUPPRIMÉE (#218, #86) — ne pas la réintroduire.
    //
    // `save`, `update`, `delete` et `confirmerPresence` ont été retirés avec les
    // endpoints POST/PUT/DELETE /api/assignments et PATCH /api/assignments/{id}/presence.
    //
    // Un assignment est le PRODUIT de la génération du circuit (carré latin,
    // RotationGenerationService) : c'est de l'état dérivé, pas une ressource qu'on
    // rédige. Aucun des quatre n'avait de garde — mesuré en direct le 2026-08-11,
    // l'évaluateur 3 a retourné la présence sur la rotation de l'évaluateur 6
    // (PATCH → 200, présence t → f). Et zéro appelant : aucun littéral
    // « /assignments » dans `frontend-web/src` ni dans `epos_mobile/lib`.
    //
    // `confirmerPresence` était le seul des quatre à avoir un usage plausible, et il
    // aurait exigé une garde COMPOSITE (l'évaluateur doit posséder la rotation ; le
    // responsable doit être dans la matière) — un primitif « OU » qu'aucun autre
    // chemin ne réclame. La présence a d'ailleurs déjà son acte, utilisé lui :
    //   PATCH /api/lots/{lotId}/presence  →  LotAssignmentService.markPresence
    // borné à la matière (#274) et posé sur le LOT, c'est-à-dire au grain où le
    // responsable travaille réellement le jour J.
    //
    // Les lectures ci-dessus sont conservées et restent filtrées au périmètre de
    // l'évaluateur.
    // =========================================================================
}