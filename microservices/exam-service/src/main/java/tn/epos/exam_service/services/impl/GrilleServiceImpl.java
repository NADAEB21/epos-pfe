package tn.epos.exam_service.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.epos.exam_service.dto.request.GrilleRequest;
import tn.epos.exam_service.dto.request.ItemRequest;
import tn.epos.exam_service.dto.response.GrilleResponse;
import tn.epos.exam_service.dto.response.ItemResponse;
import tn.epos.exam_service.entities.GrilleEvaluation;
import tn.epos.exam_service.entities.ItemEvaluation;
import tn.epos.exam_service.entities.Station;
import tn.epos.exam_service.enums.TypeItem;
import tn.epos.exam_service.exception.BusinessException;
import tn.epos.exam_service.exception.ResourceNotFoundException;
import tn.epos.exam_service.repositories.GrilleEvaluationRepository;
import tn.epos.exam_service.repositories.ItemEvaluationRepository;
import tn.epos.exam_service.repositories.StationRepository;
import tn.epos.exam_service.services.GrilleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class GrilleServiceImpl implements GrilleService {
    private final GrilleEvaluationRepository grilleRepository;
    private final ItemEvaluationRepository itemRepository;
    private final StationRepository stationRepository;
    private static final String RESOURCE_NAME = "Grille";


    // crud grille
    @Override
    public GrilleResponse creerPourStation(Long stationId, GrilleRequest request) {
        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new ResourceNotFoundException("Station", stationId));

        // Règle : une station ne peut avoir qu'une seule grille
        if (grilleRepository.existsByStationId(stationId)) {
            throw new BusinessException(
                    "La station '" + station.getNom() + "' possède déjà une grille d'évaluation. "
                            + "Utilisez PUT /grilles/{id} pour la modifier."
            );
        }

        // Règle : interdire si examen non modifiable
        if (!station.getExamen().isGrilleModifiable()) {
            throw new BusinessException(
                    "Impossible de créer une grille : l'examen est au statut "
                            + station.getExamen().getStatut()
            );
        }

        GrilleEvaluation grille = GrilleEvaluation.builder()
                .nom(request.getNom())
                .noteMax(request.getNoteMax())
                .description(request.getDescription())
                .station(station)
                .build();

        // Ajouter les items si fournis dans la requête (création groupée)
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            for (ItemRequest itemReq : request.getItems()) {
                ItemEvaluation item = buildItem(itemReq);
                grille.addItem(item);
            }
        }

        GrilleEvaluation sauvegardee = grilleRepository.save(grille);
        log.info("Grille '{}' créée pour la station {} ({} items)",
                request.getNom(), stationId, sauvegardee.getItems().size());
        return toResponse(sauvegardee);
    }

    @Override
    @Transactional(readOnly = true)
    public GrilleResponse trouverParStation(Long stationId) {
        GrilleEvaluation grille = grilleRepository.findByStationIdWithItems(stationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Aucune grille trouvée pour la station " + stationId
                ));
        return toResponse(grille);
    }

    @Override
    @Transactional(readOnly = true)
    public GrilleResponse trouverParId(Long id) {
        GrilleEvaluation grille = grilleRepository.findByIdWithItems(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NAME, id));
        return toResponse(grille);
    }

    @Override
    public GrilleResponse modifier(Long id, GrilleRequest request) {
        GrilleEvaluation grille = trouverEntite(id);

        // Règle : interdire modification si examen EN_COURS ou plus
        if (!grille.getStation().getExamen().isGrilleModifiable()) {
            throw new BusinessException(
                    "Impossible de modifier la grille : l'examen est au statut "
                            + grille.getStation().getExamen().getStatut()
            );
        }

        grille.setNom(request.getNom());
        grille.setNoteMax(request.getNoteMax());
        grille.setDescription(request.getDescription());

        return toResponse(grilleRepository.save(grille));
    }

    @Override
    public void supprimer(Long id) {
        GrilleEvaluation grille = trouverEntite(id);

        if (!grille.getStation().getExamen().isGrilleModifiable()) {
            throw new BusinessException(
                    "Impossible de supprimer la grille : l'examen est au statut "
                            + grille.getStation().getExamen().getStatut()
            );
        }

        grilleRepository.delete(grille);
        log.info("Grille {} supprimée (avec {} items en cascade)", id, grille.getItems().size());
    }




    // crud items
    @Override
    public ItemResponse ajouterItem(Long grilleId, ItemRequest request) {
        GrilleEvaluation grille = grilleRepository.findByIdWithItems(grilleId)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NAME, grilleId));

        // Règle : interdire si examen non modifiable
        if (!grille.getStation().getExamen().isGrilleModifiable()) {
            throw new BusinessException(
                    "Impossible d'ajouter un critère : l'examen est au statut "
                            + grille.getStation().getExamen().getStatut()
            );
        }

        // Validation métier : item NUMERIQUE doit avoir valeurMax
        validerItem(request);

        // Vérifier que la pondération ne va pas dépasser noteMax
        double nouvelleSomme = grille.getSommePonderations() + request.getPonderation();
        if (nouvelleSomme > grille.getNoteMax()) {
            throw new BusinessException(
                    String.format(
                            "Ajout impossible : la somme des pondérations (%.1f + %.1f = %.1f) "
                                    + "dépasserait la note maximale de la grille (%.1f)",
                            grille.getSommePonderations(), request.getPonderation(),
                            nouvelleSomme, grille.getNoteMax()
                    )
            );
        }

        ItemEvaluation item = buildItem(request);
        grille.addItem(item); // gère l'ordre automatiquement
        grilleRepository.save(grille);

        log.info("Item '{}' ({}) ajouté à la grille {}. Somme pondérations : {}/{}",
                request.getLibelle(), request.getType(), grilleId,
                nouvelleSomme, grille.getNoteMax());

        return toItemResponse(item);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ItemResponse> listerItems(Long grilleId, Pageable pageable) {
        if (!grilleRepository.existsById(grilleId)) {
            throw new ResourceNotFoundException(RESOURCE_NAME, grilleId);
        }
        return itemRepository.findByGrilleIdOrderByOrdreAsc(grilleId, pageable)
                .map(this::toItemResponse);
    }

    @Override
    public ItemResponse modifierItem(Long itemId, ItemRequest request) {
        ItemEvaluation item = trouverItem(itemId);

        // Règle : interdire si examen non modifiable
        if (!item.getGrille().getStation().getExamen().isGrilleModifiable()) {
            throw new BusinessException(
                    "Impossible de modifier le critère : l'examen est au statut "
                            + item.getGrille().getStation().getExamen().getStatut()
            );
        }

        validerItem(request);

        // Vérifier que la nouvelle pondération ne fait pas dépasser noteMax
        double sommeSansCetItem = item.getGrille().getSommePonderations() - item.getPonderation();
        double nouvelleSomme = sommeSansCetItem + request.getPonderation();
        if (nouvelleSomme > item.getGrille().getNoteMax()) {
            throw new BusinessException(
                    String.format(
                            "Modification impossible : la somme des pondérations (%.1f) "
                                    + "dépasserait la note maximale (%.1f)",
                            nouvelleSomme, item.getGrille().getNoteMax()
                    )
            );
        }

        item.setLibelle(request.getLibelle());
        item.setType(request.getType());
        item.setPonderation(request.getPonderation());
        item.setValeurMax(request.getType() == TypeItem.NUMERIQUE ? request.getValeurMax() : null);
        item.setCategorie(request.getCategorie());

        return toItemResponse(itemRepository.save(item));
    }

    @Override
    public void supprimerItem(Long itemId) {
        ItemEvaluation item = trouverItem(itemId);
        GrilleEvaluation grille = item.getGrille();

        if (!grille.getStation().getExamen().isGrilleModifiable()) {
            throw new BusinessException(
                    "Impossible de supprimer le critère : l'examen est au statut "
                            + grille.getStation().getExamen().getStatut()
            );
        }

        grille.removeItem(item); // gère la réordination automatiquement
        grilleRepository.save(grille);
        log.info("Item {} supprimé de la grille {}. Items réordonnés.", itemId, grille.getId());
    }


    // MÉTHODES PRIVÉES
    private GrilleEvaluation trouverEntite(Long id) {
        return grilleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NAME, id));
    }

    private ItemEvaluation trouverItem(Long itemId) {
        return itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item", itemId));
    }

     // Valide les règles spécifiques aux items :
     // - NUMERIQUE : valeurMax obligatoire, > 0, et ≤ pondération
     // - BINAIRE   : valeurMax ignorée

    private void validerItem(ItemRequest request) {
        if (request.getType() == TypeItem.NUMERIQUE) {
            if (request.getValeurMax() == null) {
                throw new BusinessException(
                        "Un item NUMERIQUE doit avoir une valeurMax (ex: 6 pour 'Calcul de la masse')"
                );
            }
            if (request.getValeurMax() <= 0) {
                throw new BusinessException("La valeurMax doit être supérieure à 0");
            }
            if (request.getValeurMax() > request.getPonderation()) {
                throw new BusinessException(
                        String.format(
                                "La valeurMax (%.1f) ne peut pas dépasser la pondération (%.1f)",
                                request.getValeurMax(), request.getPonderation()
                        )
                );
            }
        }
    }

    private ItemEvaluation buildItem(ItemRequest request) {
        return ItemEvaluation.builder()
                .libelle(request.getLibelle())
                .type(request.getType())
                .ponderation(request.getPonderation())
                .valeurMax(request.getType() == TypeItem.NUMERIQUE ? request.getValeurMax() : null)
                .categorie(request.getCategorie())
                .build();
    }

    // MAPPING ENTITY → DTO

    private GrilleResponse toResponse(GrilleEvaluation grille) {
        GrilleResponse response = new GrilleResponse();
        response.setId(grille.getId());
        response.setNom(grille.getNom());
        response.setNoteMax(grille.getNoteMax());
        response.setDescription(grille.getDescription());
        response.setStationId(grille.getStation().getId());
        response.setSommePonderations(grille.getSommePonderations());
        response.setPonderationValide(grille.isPonderationValide());
        response.setNombreItems(grille.getItems().size());
        response.setCreatedAt(grille.getCreatedAt());
        response.setUpdatedAt(grille.getUpdatedAt());

        List<ItemResponse> items = grille.getItems().stream()
                .map(this::toItemResponse)
                .collect(Collectors.toList());
        response.setItems(items);

        return response;
    }

    private ItemResponse toItemResponse(ItemEvaluation item) {
        ItemResponse response = new ItemResponse();
        response.setId(item.getId());
        response.setLibelle(item.getLibelle());
        response.setType(item.getType());
        response.setPonderation(item.getPonderation());
        response.setValeurMax(item.getValeurMax());
        response.setOrdre(item.getOrdre());
        response.setCategorie(item.getCategorie());
        response.setGrilleId(item.getGrille().getId());
        response.setCreatedAt(item.getCreatedAt());
        return response;
    }
}
