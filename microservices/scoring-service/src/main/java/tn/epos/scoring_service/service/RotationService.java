package tn.epos.scoring_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tn.epos.scoring_service.config.EvaluateurScopeChecker;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.repositories.IRotationRepository;

import java.util.List;
import java.util.Optional;

@Service
public class RotationService {

    @Autowired
    private IRotationRepository rotationRepository;

    @Autowired
    private EvaluateurScopeChecker scopeChecker;

    // Récupérer toutes les rotations — filtrées au périmètre de l'évaluateur (#91)
    public List<Rotation> findAll() {
        return scoped(rotationRepository.findAll());
    }

    // Récupérer une rotation par ID
    public Optional<Rotation> findById(Long id) {
        return rotationRepository.findById(id);
    }

    // Récupérer toutes les rotations d'un groupe — filtrées (#91)
    public List<Rotation> findByGroup(Long groupId) {
        return scoped(rotationRepository.findByStudentGroupId(groupId));
    }

    // Récupérer toutes les rotations d'une station (cross-service) — filtrées (#91)
    public List<Rotation> findByStation(Long stationId) {
        return scoped(rotationRepository.findByStationId(stationId));
    }

    // Filtre une liste de rotations au périmètre de l'évaluateur appelant.
    // Un appelant non contraint (SUPER_ADMIN / RESPONSABLE_MATIERE) voit tout.
    private List<Rotation> scoped(List<Rotation> rotations) {
        if (scopeChecker.peutLireHorsPerimetre()) {
            return rotations;
        }
        return rotations.stream()
                .filter(r -> scopeChecker.isCaller(r.getEvaluateurId()))
                .toList();
    }

    // =========================================================================
    // ÉCRITURE BRUTE SUPPRIMÉE (#86, #219) — ne pas la réintroduire.
    //
    // `save`, `update` et `delete` ont été retirés avec les endpoints
    // POST/PUT/DELETE /api/rotations. Trois raisons, dans cet ordre :
    //
    // 1. UNE ROTATION EST DE L'ÉTAT DÉRIVÉ, pas une ressource qu'on rédige.
    //    Le circuit est construit par RotationGenerationService (carré latin) à partir
    //    des présents. Écrire une rotation à la main produit un circuit incohérent que
    //    rien ne rattrape.
    // 2. `update` laissait ÉCRIRE `statut` À LA MAIN. C'est exactement le maquillage
    //    qu'ADR-0014 §4 interdit (« le statut se DÉRIVE de l'état réel, on ne l'IMPOSE
    //    jamais ») — et le Javadoc de LotOuvertureService affirmait que cet état « ne
    //    peut pas être maquillé à la main via PUT /api/lots/{id} ». C'était vrai pour
    //    les lots, faux pour les rotations : la porte était juste à côté.
    // 3. `delete` était un `deleteById` nu : il emportait en cascade les notations du
    //    groupe, VERROUILLÉES comprises, avec leur piste d'audit (#219).
    //
    // Et aucune garde ne les protégeait : mesuré en direct le 2026-08-11, un
    // responsable de Toxicologie a modifié la rotation 276 d'un examen de Chimie
    // (PUT → 200, ordre_passage 2 → 99). Zéro appelant dans les deux clients — aucun
    // littéral d'écriture vers /api/rotations dans `frontend-web/src`, rien côté Flutter.
    //
    // Le seul chemin légitime reste la GÉNÉRATION, déjà bornée à la matière (#274) :
    //   POST /api/rotations/lots/{lotId}/generer
    //   POST /api/rotations/examens/{examenId}/reset
    // Les lectures ci-dessus sont conservées et restent filtrées au périmètre de
    // l'évaluateur.
    // =========================================================================
}