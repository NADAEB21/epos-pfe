package tn.epos.scoring_service.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tn.epos.scoring_service.entities.NotationItem;

import java.util.List;
import java.util.Optional;

@Repository
public interface INotationItemRepository extends JpaRepository<NotationItem, Long> {

    // Trouver tous les items d'une notation spécifique
    List<NotationItem> findByNotationId(Long notationId);


    /**
     * Retrouve un NotationItem par sa notation parente et l'ID du critère.
     *
     * Utilisé par saisirNotation() pour implémenter une logique upsert :
     *   - Si le NotationItem existe → mise à jour de la valeur
     *   - Sinon → création d'un nouveau NotationItem
     *
     * On utilise @Query car le champ "item_id" contient un underscore
     * ce qui perturbe le parser Spring Data JPA (il tenterait de naviguer
     * dans une relation "item" inexistante).
     */
    @Query("SELECT ni FROM NotationItem ni " +
            "WHERE ni.notation.id = :notationId AND ni.itemId = :itemId")
    Optional<NotationItem> findByNotationIdAndItemId(
            @Param("notationId") Long notationId,
            @Param("itemId") Long itemId);

    /**
     * #361 — chargement groupé des items de TOUTES les notations d'un examen
     * (recalcul délibéré à la lecture, ADR-0030 D4) : une requête pour l'écran
     * Résultats entier, pas une par notation.
     */
    @Query("SELECT ni FROM NotationItem ni WHERE ni.notation.id IN :notationIds")
    List<NotationItem> findByNotationIdIn(@Param("notationIds") java.util.Collection<Long> notationIds);
}