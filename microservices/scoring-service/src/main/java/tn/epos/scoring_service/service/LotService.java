package tn.epos.scoring_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.repositories.ILotRepository;

import java.util.List;
import java.util.Optional;

/**
 * CRUD brut sur les lots. Les actes métier (répartir, pointer la présence, ouvrir une vague,
 * changer de jour) vivent dans {@link LotAssignmentService} et {@link LotOuvertureService}.
 *
 * <p><b>#274 — les écritures sont bornées à la matière de l'examen du lot.</b> Les lectures ne
 * le sont pas : {@code findAll} / {@code findByExamenId} restent ouvertes à la supervision
 * (ADR-0018 D5 — « accéder aux données est une LECTURE »), et borner les listes est un chantier
 * distinct.
 */
@Service
public class LotService {

    @Autowired
    private ILotRepository lotRepository;

    @Autowired
    private MatiereAccessGuard matiereAccessGuard;

    public List<Lot> findAll() {
        return lotRepository.findAll();
    }

    public List<Lot> findByExamenId(Long examenId) {
        return lotRepository.findByExamenId(examenId);
    }

    public Optional<Lot> findById(Long id) {
        return lotRepository.findById(id);
    }

    public Lot save(Lot lot) {
        // #274 — un lot sans examen n'est rattachable à aucune matière : le guard échoue fermé
        // sur null, ce qui vaut mieux qu'un lot orphelin créé par n'importe quel responsable.
        matiereAccessGuard.checkExamenAccess(lot.getExamenId());
        return lotRepository.save(lot);
    }

    public void delete(Long id) {
        // Il faut charger avant de supprimer : `deleteById` ne dit pas à quel examen le lot
        // appartenait, donc ne permet aucune vérification de périmètre.
        lotRepository.findById(id).ifPresent(lot -> {
            matiereAccessGuard.checkExamenAccess(lot.getExamenId());
            lotRepository.delete(lot);
        });
    }

    public Lot update(Long id, Lot lotDetails) {
        return lotRepository.findById(id).map(lot -> {
            // #274 — le périmètre de DÉPART, avant toute modification.
            matiereAccessGuard.checkExamenAccess(lot.getExamenId());
            // Et celui d'ARRIVÉE si la charge utile réattribue l'examen : sans cette seconde
            // vérification, déplacer un lot vers un examen d'une autre matière serait le
            // contournement de la première. Le contrôleur ne mappe pas `examenId` aujourd'hui
            // (donc le cas ne se présente pas), mais une garde ne doit pas dépendre de ce que
            // le mapper voisin omet de faire.
            if (lotDetails.getExamenId() != null
                    && !lotDetails.getExamenId().equals(lot.getExamenId())) {
                matiereAccessGuard.checkExamenAccess(lotDetails.getExamenId());
            }
            lot.setNumeroLot(lotDetails.getNumeroLot());
            lot.setTailleLot(lotDetails.getTailleLot());
            lot.setStatut(lotDetails.getStatut());
            // #215 sémantique PATCH : le PUT ne peuple PAS examenId/evaluateurId
            // (le contrôleur ne les mappe pas) — les copier écraserait les FK à
            // null et détacherait le lot de son examen/évaluateur.
            if (lotDetails.getEvaluateurId() != null) {
                lot.setEvaluateurId(lotDetails.getEvaluateurId());
            }
            if (lotDetails.getExamenId() != null) {
                lot.setExamenId(lotDetails.getExamenId());
            }
            // #147 — jour multi-jour : même sémantique PATCH que les FK ci-dessus.
            // On ne l'écrase que s'il est fourni ; un PUT qui ne le porte pas ne
            // ré-attribue pas le lot au jour unique de l'examen par accident.
            if (lotDetails.getJour() != null) {
                lot.setJour(lotDetails.getJour());
            }
            return lotRepository.save(lot);
        }).orElseThrow(() -> new ResourceNotFoundException("Lot non trouvé avec l'id : " + id));
    }
}