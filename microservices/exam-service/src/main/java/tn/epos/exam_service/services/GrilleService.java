package tn.epos.exam_service.services;

import tn.epos.exam_service.dto.request.GrilleRequest;
import tn.epos.exam_service.dto.request.ItemRequest;
import tn.epos.exam_service.dto.response.GrilleResponse;
import tn.epos.exam_service.dto.response.ItemResponse;

import java.util.List;
public interface GrilleService {
    /** Créer et associer une grille à une station (avec items optionnels) */
    GrilleResponse creerPourStation(Long stationId, GrilleRequest request);

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
    List<ItemResponse> listerItems(Long grilleId);

    /** Modifier un item */
    ItemResponse modifierItem(Long itemId, ItemRequest request);

    /** Supprimer un item et réordonner les suivants */
    void supprimerItem(Long itemId);
}
