package tn.epos.exam_service.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.exam_service.config.MatiereAccessChecker;
import tn.epos.exam_service.dto.request.GrilleTemplateRequest;
import tn.epos.exam_service.dto.request.ItemRequest;
import tn.epos.exam_service.dto.response.ExamenExportResponse;
import tn.epos.exam_service.dto.response.GrilleTemplateResponse;
import tn.epos.exam_service.dto.response.ItemResponse;
import tn.epos.exam_service.entities.*;
import tn.epos.exam_service.enums.StatutExamen;
import tn.epos.exam_service.enums.TypeItem;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.exam_service.repositories.*;
import tn.epos.exam_service.services.GrilleTemplateService;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class GrilleTemplateServiceImpl implements GrilleTemplateService {

    private final GrilleTemplateRepository templateRepository;
    private final GrilleEvaluationRepository grilleRepository;
    private final StationRepository stationRepository;
    private final ExamenRepository examenRepository;
    private final ObjectMapper objectMapper;
    private final MatiereAccessChecker matiereAccessChecker;

    // ── BF2.4 : Sauvegarder une grille existante comme template ──────────────

    @Override
    public GrilleTemplateResponse sauvegarderDepuisGrille(Long grilleId, String nomTemplate) {
        GrilleEvaluation grille = grilleRepository.findByIdWithItems(grilleId)
                .orElseThrow(() -> new ResourceNotFoundException("Grille", grilleId));
        matiereAccessChecker.checkAccess(grille.getStation().getExamen().getMatiereId());

        if (templateRepository.existsByNom(nomTemplate)) {
            throw new BusinessException("Un template nommé '" + nomTemplate + "' existe déjà");
        }

        GrilleTemplate template = GrilleTemplate.builder()
                .nom(nomTemplate)
                .description(grille.getDescription())
                .noteMax(grille.getNoteMax())
                .build();

        // #160 : grille.getItems() ne contient que les critères de premier niveau
        // (voir GrilleEvaluation.addItem) ; buildItemTemplate() descend récursivement
        // dans les sous-critères.
        grille.getItems().forEach(item -> template.addItem(buildItemTemplate(item)));

        GrilleTemplate saved = templateRepository.save(template);
        log.info("Template '{}' créé depuis la grille {}", nomTemplate, grilleId);
        return toResponse(saved);
    }

    @Override
    public GrilleTemplateResponse creer(GrilleTemplateRequest request) {
        if (templateRepository.existsByNom(request.getNom())) {
            throw new BusinessException("Un template nommé '" + request.getNom() + "' existe déjà");
        }

        GrilleTemplate template = GrilleTemplate.builder()
                .nom(request.getNom())
                .description(request.getDescription())
                .noteMax(request.getNoteMax())
                .build();

        if (request.getItems() != null) {
            for (int i = 0; i < request.getItems().size(); i++) {
                ItemRequest ir = request.getItems().get(i);
                ItemTemplate it = ItemTemplate.builder()
                        .libelle(ir.getLibelle())
                        .type(ir.getType())
                        .ponderation(ir.getPonderation())
                        .valeurMax(ir.getValeurMax())
                        .categorie(ir.getCategorie())
                        .valeurAttendue(ir.getValeurAttendue())
                        .conditionsAttendues(ir.getConditionsAttendues())
                        .ordre(i + 1)
                        .build();
                template.addItem(it);
            }
        }

        return toResponse(templateRepository.save(template));
    }

    @Override
    @Transactional(readOnly = true)
    public GrilleTemplateResponse trouverParId(Long id) {
        return toResponse(templateRepository.findByIdWithItems(id)
                .orElseThrow(() -> new ResourceNotFoundException("Template", id)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<GrilleTemplateResponse> listerTous() {
        return templateRepository.findAllWithItems()
                .stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Override
    public void supprimer(Long id) {
        GrilleTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Template", id));
        templateRepository.delete(template);
        log.info("Template {} supprimé", id);
    }

    // ── BF2.4 : Appliquer un template sur une station ────────────────────────

    @Override
    public void appliquerSurStation(Long templateId, Long stationId) {
        GrilleTemplate template = templateRepository.findByIdWithItems(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Template", templateId));

        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new ResourceNotFoundException("Station", stationId));
        matiereAccessChecker.checkAccess(station.getExamen().getMatiereId());

        if (!station.getExamen().isGrilleModifiable()) {
            throw new BusinessException("Impossible d'appliquer un template : l'examen est au statut "
                    + station.getExamen().getStatut());
        }

        // Remplacement EN PLACE : réutiliser la grille existante si présente plutôt
        // que delete+insert. Deux lignes ne peuvent coexister sur le station_id
        // unique, et Hibernate ordonnait l'INSERT de la nouvelle grille avant le
        // DELETE de l'ancienne dans un même flush → violation de
        // grilles_evaluation_station_id_key (SQLState 23505). Mettre à jour la grille
        // existante évite le conflit et préserve son id.
        GrilleEvaluation grille = grilleRepository.findByStationIdWithItems(stationId)
                .orElseGet(() -> GrilleEvaluation.builder().station(station).build());

        grille.setNom(template.getNom());
        grille.setNoteMax(template.getNoteMax());
        grille.setDescription(template.getDescription());
        grille.getItems().clear();   // orphanRemoval supprime les anciens critères (cascade DB pour leurs enfants)

        template.getItems().forEach(it -> grille.addItem(buildItemFromTemplate(it)));

        grilleRepository.save(grille);
        log.info("Template '{}' appliqué sur la station {}", template.getNom(), stationId);
    }

    // ── BF2.5 : Export JSON complet d'un examen ──────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public ExamenExportResponse exporterExamen(Long examenId) {
        Examen examen = examenRepository.findByIdWithStations(examenId)
                .orElseThrow(() -> new ResourceNotFoundException("Examen", examenId));
        matiereAccessChecker.checkAccess(examen.getMatiereId());

        ExamenExportResponse export = new ExamenExportResponse();
        export.setNom(examen.getNom());
        export.setMatiereId(examen.getMatiereId());
        export.setDateExamen(examen.getDateExamen());
        export.setDureeStationMin(examen.getDureeStationMin());
        export.setNbEtudiantsParStation(examen.getNbEtudiantsParStation());
        export.setDescription(examen.getDescription());

        List<ExamenExportResponse.StationExportResponse> stationsExport = examen.getStations().stream()
                .map(this::buildStationExport)
                .collect(Collectors.toList());

        export.setStations(stationsExport);
        return export;
    }

    // ── BF2.4 : Duplication d'un examen ──────────────────────────────────────

    @Override
    public Long dupliquerExamen(Long examenId, String nouveauNom) {
        Examen source = examenRepository.findByIdWithStations(examenId)
                .orElseThrow(() -> new ResourceNotFoundException("Examen", examenId));
        // Caller must own the source matiere; the duplicate inherits its matiereId.
        matiereAccessChecker.checkAccess(source.getMatiereId());

        Examen copie = Examen.builder()
                .nom(nouveauNom)
                .matiereId(source.getMatiereId())
                .dateExamen(source.getDateExamen())
                .dureeStationMin(source.getDureeStationMin())
                .nbEtudiantsParStation(source.getNbEtudiantsParStation())
                // ADR-0012 : la copie hérite aussi du tampon inter-créneau et du
                // délai d'avertissement, sinon dupliquer un examen réinitialise
                // silencieusement sa configuration de transition à 0.
                .tempsBattementMin(source.getTempsBattementMin())
                .avertissementLeadSec(source.getAvertissementLeadSec())
                .description(source.getDescription())
                .statut(StatutExamen.BROUILLON)
                .build();

        Examen examenSauve = examenRepository.save(copie);
        source.getStations().forEach(station -> dupliquerStation(station, examenSauve));

        log.info("Examen {} dupliqué → nouvel examen {} ('{}')", examenId, examenSauve.getId(), nouveauNom);
        return examenSauve.getId();
    }

    // ── BF2.5 : Import grille depuis JSON ────────────────────────────────────

    @Override
    public void importerGrilleJson(Long stationId, String grilleJson) {
        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new ResourceNotFoundException("Station", stationId));
        matiereAccessChecker.checkAccess(station.getExamen().getMatiereId());

        if (!station.getExamen().isGrilleModifiable()) {
            throw new BusinessException("Impossible d'importer : l'examen est au statut "
                    + station.getExamen().getStatut());
        }

        ExamenExportResponse.GrilleExportResponse importedGrille;
        try {
            importedGrille = objectMapper.readValue(grilleJson,
                    ExamenExportResponse.GrilleExportResponse.class);
        } catch (Exception e) {
            throw new BusinessException("Format JSON invalide : " + e.getMessage());
        }

        // Remplacement EN PLACE (même raison que appliquerSurStation : éviter la
        // violation de contrainte unique station_id due à l'ordonnancement du flush).
        GrilleEvaluation grille = grilleRepository.findByStationIdWithItems(stationId)
                .orElseGet(() -> GrilleEvaluation.builder().station(station).build());

        grille.setNom(importedGrille.getNom());
        grille.setNoteMax(importedGrille.getNoteMax());
        grille.setDescription(importedGrille.getDescription());
        grille.getItems().clear();   // orphanRemoval supprime les anciens critères

        if (importedGrille.getItems() != null) {
            importedGrille.getItems().forEach(ie -> grille.addItem(buildItemFromExport(ie)));
        }

        grilleRepository.save(grille);
        log.info("Grille importée depuis JSON sur la station {}", stationId);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers récursifs — arbre critère / sous-critères (#160)
    //
    // Une méthode Java ne peut être déclarée qu'au niveau de la classe, jamais
    // à l'intérieur d'une autre méthode : ces helpers sont donc des méthodes
    // privées de la classe, appelées par les méthodes publiques ci-dessus.
    // ═══════════════════════════════════════════════════════════════════════

    /** Grille (entité) → Template (entité), récursif. */
    private ItemTemplate buildItemTemplate(ItemEvaluation item) {
        ItemTemplate it = ItemTemplate.builder()
                .libelle(item.getLibelle())
                .type(item.getType())
                .ponderation(item.getPonderation())
                .valeurMax(item.getValeurMax())
                .categorie(item.getCategorie())
                .valeurAttendue(item.getValeurAttendue())
                .conditionsAttendues(item.getConditionsAttendues())
                .ordre(item.getOrdre())
                .build();
        item.getChildren().forEach(enfant -> it.addChild(buildItemTemplate(enfant)));
        return it;
    }

    /** Template (entité) → Grille (entité fraîche, sans id), récursif. */
    private ItemEvaluation buildItemFromTemplate(ItemTemplate it) {
        ItemEvaluation item = ItemEvaluation.builder()
                .libelle(it.getLibelle())
                .type(it.getType())
                .ponderation(it.getPonderation())
                .valeurMax(it.getValeurMax())
                .categorie(it.getCategorie())
                .valeurAttendue(it.getValeurAttendue())
                .conditionsAttendues(it.getConditionsAttendues())
                .ordre(it.getOrdre())
                .build();
        it.getChildren().forEach(enfant -> item.addChild(buildItemFromTemplate(enfant)));
        return item;
    }

    /** Template (entité) → DTO exposé au client, récursif. */
    private ItemResponse toItemTemplateResponse(ItemTemplate it) {
        ItemResponse ir = new ItemResponse();
        ir.setId(it.getId());
        ir.setLibelle(it.getLibelle());
        ir.setType(it.getType());
        ir.setPonderation(it.getPonderation());
        ir.setValeurMax(it.getValeurMax());
        ir.setOrdre(it.getOrdre());
        ir.setCategorie(it.getCategorie());
        ir.setValeurAttendue(it.getValeurAttendue());
        ir.setConditionsAttendues(it.getConditionsAttendues());
        ir.setHasSousCriteres(!it.getChildren().isEmpty());
        ir.setSousCriteres(it.getChildren().stream()
                .map(this::toItemTemplateResponse)
                .collect(Collectors.toList()));
        return ir;
    }

    /** Station (entité, avec sa grille) → DTO d'export, récursif sur les items. */
    private ExamenExportResponse.StationExportResponse buildStationExport(Station station) {
        ExamenExportResponse.StationExportResponse se = new ExamenExportResponse.StationExportResponse();
        se.setNom(station.getNom());
        se.setType(station.getType().name());
        se.setDescription(station.getDescription());
        se.setOrdre(station.getOrdre());

        grilleRepository.findByStationIdWithItems(station.getId()).ifPresent(grille -> {
            ExamenExportResponse.GrilleExportResponse ge = new ExamenExportResponse.GrilleExportResponse();
            ge.setNom(grille.getNom());
            ge.setNoteMax(grille.getNoteMax());
            ge.setDescription(grille.getDescription());
            ge.setItems(grille.getItems().stream()
                    .map(this::buildItemExport)
                    .collect(Collectors.toList()));
            se.setGrille(ge);
        });
        return se;
    }

    /** Item d'évaluation (entité) → DTO d'export, récursif. */
    private ExamenExportResponse.ItemExportResponse buildItemExport(ItemEvaluation item) {
        ExamenExportResponse.ItemExportResponse ie = new ExamenExportResponse.ItemExportResponse();
        ie.setLibelle(item.getLibelle());
        ie.setType(item.getType().name());
        ie.setPonderation(item.getPonderation());
        ie.setValeurMax(item.getValeurMax());
        ie.setCategorie(item.getCategorie());
        ie.setValeurAttendue(item.getValeurAttendue());
        ie.setConditionsAttendues(item.getConditionsAttendues());
        ie.setOrdre(item.getOrdre());
        ie.setSousCriteres(item.getChildren().stream()
                .map(this::buildItemExport)
                .collect(Collectors.toList()));
        return ie;
    }

    /** DTO d'export → Item d'évaluation (entité fraîche, sans id), récursif. */
    private ItemEvaluation buildItemFromExport(ExamenExportResponse.ItemExportResponse ie) {
        ItemEvaluation item = ItemEvaluation.builder()
                .libelle(ie.getLibelle())
                .type(TypeItem.valueOf(ie.getType()))
                .ponderation(ie.getPonderation())
                .valeurMax(ie.getValeurMax())
                .categorie(ie.getCategorie())
                .valeurAttendue(ie.getValeurAttendue())
                .conditionsAttendues(ie.getConditionsAttendues())
                .ordre(ie.getOrdre())
                .build();
        if (ie.getSousCriteres() != null) {
            ie.getSousCriteres().forEach(enfant -> item.addChild(buildItemFromExport(enfant)));
        }
        return item;
    }

    /** Copie une station (avec sa grille et tout l'arbre de critères) vers un nouvel examen. */
    private void dupliquerStation(Station station, Examen examenCible) {
        Station nouvelleStation = Station.builder()
                .nom(station.getNom())
                .type(station.getType())
                .description(station.getDescription())
                .ordre(station.getOrdre())
                .examen(examenCible)
                .build();
        stationRepository.save(nouvelleStation);

        grilleRepository.findByStationIdWithItems(station.getId()).ifPresent(grille -> {
            GrilleEvaluation nouvelleGrille = GrilleEvaluation.builder()
                    .nom(grille.getNom())
                    .noteMax(grille.getNoteMax())
                    .description(grille.getDescription())
                    .station(nouvelleStation)
                    .build();

            grille.getItems().forEach(item -> nouvelleGrille.addItem(copierItemRecursivement(item)));
            grilleRepository.save(nouvelleGrille);
        });
    }

    /** Copie profonde d'un item d'évaluation et de tous ses sous-critères, sans id. */
    private ItemEvaluation copierItemRecursivement(ItemEvaluation source) {
        ItemEvaluation copie = ItemEvaluation.builder()
                .libelle(source.getLibelle())
                .type(source.getType())
                .ponderation(source.getPonderation())
                .valeurMax(source.getValeurMax())
                .categorie(source.getCategorie())
                .valeurAttendue(source.getValeurAttendue())
                .conditionsAttendues(source.getConditionsAttendues())
                .ordre(source.getOrdre())
                .build();
        source.getChildren().forEach(enfant -> copie.addChild(copierItemRecursivement(enfant)));
        return copie;
    }

    // ── Mapping template → DTO ────────────────────────────────────────────────

    private GrilleTemplateResponse toResponse(GrilleTemplate template) {
        GrilleTemplateResponse r = new GrilleTemplateResponse();
        r.setId(template.getId());
        r.setNom(template.getNom());
        r.setDescription(template.getDescription());
        r.setNoteMax(template.getNoteMax());
        r.setCreatedAt(template.getCreatedAt());
        r.setNombreItems(template.getItems().size());
        r.setSommePonderations(template.getItems().stream()
                .mapToDouble(ItemTemplate::getPonderation).sum());
        r.setItems(template.getItems().stream()
                .map(this::toItemTemplateResponse)
                .collect(Collectors.toList()));
        return r;
    }
}