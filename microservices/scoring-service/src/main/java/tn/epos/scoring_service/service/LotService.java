package tn.epos.scoring_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.repositories.ILotRepository;

import java.util.List;
import java.util.Optional;

@Service
public class LotService {

    @Autowired
    private ILotRepository lotRepository;

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
        return lotRepository.save(lot);
    }

    public void delete(Long id) {
        lotRepository.deleteById(id);
    }

    public Lot update(Long id, Lot lotDetails) {
        return lotRepository.findById(id).map(lot -> {
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
            return lotRepository.save(lot);
        }).orElseThrow(() -> new ResourceNotFoundException("Lot non trouvé avec l'id : " + id));
    }
}