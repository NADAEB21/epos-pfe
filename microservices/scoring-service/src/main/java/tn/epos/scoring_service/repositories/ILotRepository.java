package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.epos.scoring_service.entities.Lot;

import java.util.List;
import java.util.Optional;

@Repository
public interface ILotRepository extends JpaRepository<Lot, Long> {
    // ── Méthodes existantes (à conserver) ────────────────────────────────────
    // (les méthodes déjà définies dans l'interface existante restent inchangées)

    // ── Nouvelles méthodes pour EvaluateurDashboardService ───────────────────

    /**
     * Récupère tous les lots assignés à un évaluateur.
     * Utilisé pour construire les sessions du dashboard Flutter.
     */
    List<Lot> findByEvaluateurId(Long evaluateurId);

    /**
     * Trouve un lot par évaluateur et numéro de lot.
     * Utilisé par getLotDetail() pour charger le lot courant.
     */
    Optional<Lot> findByEvaluateurIdAndNumeroLot(Long evaluateurId, Integer numeroLot);

    /**
     * Compte le nombre total de lots pour un examen donné.
     * Utilisé pour remplir le champ "totalLots" dans LotDetailResponse.
     */
    int countByExamenId(Long examenId);

    // Lots d'un examen (cross-service examen_id) — utilisé par la génération
    // des rotations pour purger l'existant avant régénération.
    List<Lot> findByExamenId(Long examenId);
}