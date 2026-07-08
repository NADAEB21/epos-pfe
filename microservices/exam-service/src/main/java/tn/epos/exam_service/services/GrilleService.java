package tn.epos.exam_service.services;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.epos.exam_service.dto.request.GrilleRequest;
import tn.epos.exam_service.dto.request.ItemRequest;
import tn.epos.exam_service.dto.response.GrilleResponse;
import tn.epos.exam_service.dto.response.ItemResponse;

import java.util.List;

public interface GrilleService {
    /** Créer et associer une grille à une station (avec items optionnels) */
    GrilleResponse creerPourStation(Long stationId, GrilleRequest request);

    /**
     * #161 : « Remplacer la grille » — pose la grille de la station EN PLACE
     * (crée si absente, remplace items compris si présente) de façon idempotente.
     * Évite le duo delete→create côté client et la violation de contrainte unique
     * station_id, en réutilisant la ligne existante comme {@code appliquerSurStation}.
     */
    GrilleResponse remplacerPourStation(Long stationId, GrilleRequest request);

    /** Récupérer la grille d'une station avec ses items */
    GrilleResponse trouverParStation(Long stationId);

    /** Récupérer une grille par son ID */
    GrilleResponse trouverParId(Long id);

    /** Modifier les infos de la grille (nom, noteMax, description) */
    GrilleResponse modifier(Long id, GrilleRequest request);

    /** Supprimer la grille (et ses items en cascade) */
    void supprimer(Long id);

    // ===== GESTION DES ITEMS =====

    /** Ajouter un critère à une grille */
    ItemResponse ajouterItem(Long grilleId, ItemRequest request);

    /** Lister tous les items d'une grille */
    Page<ItemResponse> listerItems(Long grilleId, Pageable pageable);

    /** Modifier un item */
    ItemResponse modifierItem(Long itemId, ItemRequest request);

    /** Supprimer un item et réordonner les suivants */
    void supprimerItem(Long itemId);

    /** #160 — ajoute un sous-critère (enfant) à un item existant. Un seul niveau de profondeur. */
    ItemResponse ajouterSousCritere(Long itemParentId, ItemRequest request);

    List<ItemResponse> listerSousCriteres(Long itemParentId);

    /** Aplatit l'arbre : ne renvoie que les feuilles (celles réellement notables par l'évaluateur). */
    List<ItemResponse> listerItemsFeuilles(Long grilleId);
}
