package tn.epos.exam_service.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.epos.exam_service.config.MatiereAccessChecker;
import tn.epos.exam_service.dto.request.GrilleRequest;
import tn.epos.exam_service.dto.request.ItemRequest;
import tn.epos.exam_service.dto.response.GrilleResponse;
import tn.epos.exam_service.dto.response.ItemResponse;
import tn.epos.exam_service.entities.GrilleEvaluation;
import tn.epos.exam_service.entities.ItemEvaluation;
import tn.epos.exam_service.entities.Station;
import tn.epos.exam_service.enums.TypeItem;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.exam_service.repositories.GrilleEvaluationRepository;
import tn.epos.exam_service.repositories.ItemEvaluationRepository;
import tn.epos.exam_service.repositories.StationRepository;
import tn.epos.exam_service.services.GrilleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
    private final MatiereAccessChecker matiereAccessChecker;
    private static final String RESOURCE_NAME = "Grille";


    // crud grille
    @Override
    public GrilleResponse creerPourStation(Long stationId, GrilleRequest request) {
        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new ResourceNotFoundException("Station", stationId));
        matiereAccessChecker.checkAccess(station.getExamen().getMatiereId());

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

    // #161 : Remplacement idempotent de la grille d'une station (create-or-replace).
    // Mise à jour EN PLACE de la ligne existante — même raison que
    // GrilleTemplateServiceImpl.appliquerSurStation : deux lignes ne peuvent
    // coexister sur station_id (contrainte unique), et Hibernate ordonnerait
    // l'INSERT avant le DELETE dans un même flush → 23505. Réutiliser la grille
    // existante évite le conflit, préserve son id, et rend l'opération sûre sans
    // aucun delete→create côté client.
    @Override
    public GrilleResponse remplacerPourStation(Long stationId, GrilleRequest request) {
        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new ResourceNotFoundException("Station", stationId));
        matiereAccessChecker.checkAccess(station.getExamen().getMatiereId());

        if (!station.getExamen().isGrilleModifiable()) {
            throw new BusinessException(
                    "Impossible de remplacer la grille : l'examen est au statut "
                            + station.getExamen().getStatut()
            );
        }

        GrilleEvaluation grille = grilleRepository.findByStationIdWithItems(stationId)
                .orElseGet(() -> GrilleEvaluation.builder().station(station).build());

        grille.setNom(request.getNom());
        grille.setNoteMax(request.getNoteMax());
        grille.setDescription(request.getDescription());
        grille.getItems().clear();   // orphanRemoval supprime les anciens critères

        if (request.getItems() != null) {
            for (ItemRequest itemReq : request.getItems()) {
                grille.addItem(buildItem(itemReq));
            }
        }

        GrilleEvaluation sauvegardee = grilleRepository.save(grille);
        log.info("Grille de la station {} remplacée en place ('{}', {} items)",
                stationId, request.getNom(), sauvegardee.getItems().size());
        return toResponse(sauvegardee);
    }

    @Override
    @Transactional(readOnly = true)
    public GrilleResponse trouverParStation(Long stationId) {
        GrilleEvaluation grille = grilleRepository.findByStationIdWithItems(stationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Aucune grille trouvée pour la station " + stationId
                ));
        matiereAccessChecker.checkReadAccess(grille.getStation().getExamen().getMatiereId());
        return toResponse(grille);
    }

    @Override
    @Transactional(readOnly = true)
    public GrilleResponse trouverParId(Long id) {
        GrilleEvaluation grille = grilleRepository.findByIdWithItems(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NAME, id));
        matiereAccessChecker.checkReadAccess(grille.getStation().getExamen().getMatiereId());
        return toResponse(grille);
    }

    @Override
    public GrilleResponse modifier(Long id, GrilleRequest request) {
        GrilleEvaluation grille = trouverEntite(id);
        matiereAccessChecker.checkAccess(grille.getStation().getExamen().getMatiereId());

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
        matiereAccessChecker.checkAccess(grille.getStation().getExamen().getMatiereId());

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
        matiereAccessChecker.checkAccess(grille.getStation().getExamen().getMatiereId());

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
        GrilleEvaluation grille = grilleRepository.findById(grilleId)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NAME, grilleId));
        matiereAccessChecker.checkReadAccess(grille.getStation().getExamen().getMatiereId());
        return itemRepository.findByGrilleIdAndParentIsNullOrderByOrdreAsc(grilleId, pageable)
                .map(this::toItemResponse);
    }

    @Override
    public ItemResponse modifierItem(Long itemId, ItemRequest request) {
        ItemEvaluation item = trouverItem(itemId);
        matiereAccessChecker.checkAccess(item.getGrille().getStation().getExamen().getMatiereId());

        if (!item.getGrille().getStation().getExamen().isGrilleModifiable()) {
            throw new BusinessException(
                    "Impossible de modifier le critère : l'examen est au statut "
                            + item.getGrille().getStation().getExamen().getStatut());
        }

        validerItem(request);

        if (item.getParent() != null) {
            ItemEvaluation parent = item.getParent();
            double sommeSansCetEnfant = parent.getSommePonderationsEnfants() - item.getPonderation();
            double nouvelleSomme = sommeSansCetEnfant + request.getPonderation();
            if (nouvelleSomme > parent.getPonderation()) {
                throw new BusinessException(String.format(
                        "Modification impossible : la somme des pondérations des sous-critères (%.2f) "
                                + "dépasserait la pondération du critère parent (%.2f)",
                        nouvelleSomme, parent.getPonderation()));
            }
        } else {
            double sommeSansCetItem = item.getGrille().getSommePonderations() - item.getPonderation();
            double nouvelleSomme = sommeSansCetItem + request.getPonderation();
            if (nouvelleSomme > item.getGrille().getNoteMax()) {
                throw new BusinessException(String.format(
                        "Modification impossible : la somme des pondérations (%.1f) "
                                + "dépasserait la note maximale (%.1f)",
                        nouvelleSomme, item.getGrille().getNoteMax()));
            }
        }

        item.setLibelle(request.getLibelle());
        item.setType(request.getType());
        item.setPonderation(request.getPonderation());
        item.setValeurMax(request.getType() == TypeItem.NUMERIQUE ? request.getValeurMax() : null);
        item.setCategorie(request.getCategorie());
        item.setValeurAttendue(request.getValeurAttendue());
        item.setConditionsAttendues(request.getConditionsAttendues());

        return toItemResponse(itemRepository.save(item));
    }

    @Override
    public void supprimerItem(Long itemId) {
        ItemEvaluation item = trouverItem(itemId);
        ItemEvaluation parent = item.getParent();
        GrilleEvaluation grille = item.getGrille();
        matiereAccessChecker.checkAccess(grille.getStation().getExamen().getMatiereId());

        if (!grille.getStation().getExamen().isGrilleModifiable()) {
            throw new BusinessException(
                    "Impossible de supprimer le critère : l'examen est au statut "
                            + grille.getStation().getExamen().getStatut());
        }

        if (parent != null) {
            parent.getChildren().remove(item);
            List<ItemEvaluation> enfants = parent.getChildren();
            for (int i = 0; i < enfants.size(); i++) enfants.get(i).setOrdre(i + 1);
            itemRepository.save(parent); // orphanRemoval supprime l'enfant retiré
            log.info("Sous-critère {} supprimé du critère parent {}.", itemId, parent.getId());
        } else {
            grille.removeItem(item);
            grilleRepository.save(grille);
            log.info("Item {} supprimé. Items réordonnés.", itemId);
        }
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
                .valeurAttendue(request.getValeurAttendue())
                .conditionsAttendues(request.getConditionsAttendues())
                .build();
    }

    // sub criteria
    @Override
    public ItemResponse ajouterSousCritere(Long itemParentId, ItemRequest request) {
        ItemEvaluation parent = trouverItem(itemParentId);
        matiereAccessChecker.checkAccess(parent.getGrille().getStation().getExamen().getMatiereId());

        if (!parent.getGrille().getStation().getExamen().isGrilleModifiable()) {
            throw new BusinessException(
                    "Impossible d'ajouter un sous-critère : l'examen est au statut "
                            + parent.getGrille().getStation().getExamen().getStatut());
        }

        // AC #160 : un seul niveau de profondeur — un sous-critère ne peut pas être parent.
        if (parent.getParent() != null) {
            throw new BusinessException(
                    "Un sous-critère ne peut pas avoir lui-même des sous-critères "
                            + "(un seul niveau de profondeur est supporté).");
        }

        validerItem(request);
        ItemEvaluation enfant = buildItem(request);
        parent.addChild(enfant);

        double somme = parent.getSommePonderationsEnfants();
        if (somme > parent.getPonderation()) {
            throw new BusinessException(String.format(
                    "Ajout impossible : la somme des pondérations des sous-critères (%.2f) "
                            + "dépasserait la pondération du critère '%s' (%.2f)",
                    somme, parent.getLibelle(), parent.getPonderation()));
        }

        itemRepository.save(enfant);
        log.info("Sous-critère '{}' ajouté au critère {} (somme {}/{})",
                request.getLibelle(), itemParentId, somme, parent.getPonderation());
        return toItemResponse(enfant);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ItemResponse> listerSousCriteres(Long itemParentId) {
        ItemEvaluation parent = trouverItem(itemParentId);
        matiereAccessChecker.checkReadAccess(parent.getGrille().getStation().getExamen().getMatiereId());
        return itemRepository.findByParentIdOrderByOrdreAsc(itemParentId).stream()
                .map(this::toItemResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ItemResponse> listerItemsFeuilles(Long grilleId) {
        GrilleEvaluation grille = grilleRepository.findByIdWithItems(grilleId)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE_NAME, grilleId));
        matiereAccessChecker.checkReadAccess(grille.getStation().getExamen().getMatiereId());
        List<ItemEvaluation> topLevel = grille.getItems().stream()
                .filter(i -> i.getParent() == null)
                .collect(Collectors.toList());
        return aplatirFeuilles(topLevel);
    }

    private List<ItemResponse> aplatirFeuilles(List<ItemEvaluation> items) {
        List<ItemResponse> feuilles = new ArrayList<>();
        for (ItemEvaluation item : items) {
            if (item.hasChildren()) {
                feuilles.addAll(aplatirFeuilles(item.getChildren()));
            } else {
                feuilles.add(toItemResponse(item));
            }
        }
        return feuilles;
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
        response.setValeurAttendue(item.getValeurAttendue());
        response.setConditionsAttendues(item.getConditionsAttendues());
        response.setGrilleId(item.getGrille().getId());
        response.setCreatedAt(item.getCreatedAt());
        response.setParentId(item.getParent() != null ? item.getParent().getId() : null);
        response.setHasSousCriteres(item.hasChildren());
        response.setSousCriteres(item.getChildren().stream()
                .map(this::toItemResponse)
                .collect(Collectors.toList()));
        return response;
    }
}