package tn.epos.scoring_service.repositories;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * #432 — le lot, VERROUILLÉ ({@code SELECT … FOR UPDATE}) pour la durée de la
     * transaction appelante. Les actes du jour J qui écrivent la vague entière — présence +
     * génération, génération seule, ouverture — étaient des « lire puis écrire » sans
     * verrou : deux co-responsables sur le même lot (ou un seul, depuis une page obsolète)
     * passaient tous les deux les gardes, et le second effaçait puis régénérait un circuit
     * que les évaluateurs suivaient déjà sur leurs téléphones. Avec le verrou, le second
     * attend le commit du premier, relit l'état commis, et se heurte à la garde « vague
     * déjà démarrée » au lieu de la contourner.
     *
     * <p>Réentrant dans une même transaction : l'orchestrateur peut verrouiller, puis
     * déléguer à un service qui verrouille encore — c'est le même verrou.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Lot l where l.id = :id")
    Optional<Lot> findByIdVerrouille(@Param("id") Long id);
}