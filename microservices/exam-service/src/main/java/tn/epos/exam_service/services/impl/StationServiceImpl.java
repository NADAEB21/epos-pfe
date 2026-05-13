package tn.epos.exam_service.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.epos.exam_service.dto.request.StationRequest;
import tn.epos.exam_service.dto.response.StationResponse;
import tn.epos.exam_service.entities.Examen;
import tn.epos.exam_service.entities.Station;
import tn.epos.exam_service.exception.BusinessException;
import tn.epos.exam_service.exception.ResourceNotFoundException;
import tn.epos.exam_service.repositories.ExamenRepository;
import tn.epos.exam_service.repositories.StationRepository;
import tn.epos.exam_service.services.StationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class StationServiceImpl implements StationService {
    private final StationRepository stationRepository;
    private final ExamenRepository examenRepository;

    @Override
    public StationResponse ajouter(Long examenId, StationRequest request) {
        Examen examen = examenRepository.findById(examenId)
                .orElseThrow(() -> new ResourceNotFoundException("Examen", examenId));

        // Règle : on ne peut pas ajouter une station à un examen EN_COURS ou terminé
        if (!examen.isGrilleModifiable()) {
            throw new BusinessException(
                    "Impossible d'ajouter une station : l'examen est au statut " + examen.getStatut()
            );
        }

        // Vérifier doublon de nom dans le même examen
        if (stationRepository.existsByNomAndExamenId(request.getNom(), examenId)) {
            throw new BusinessException(
                    "Une station nommée '" + request.getNom() + "' existe déjà dans cet examen"
            );
        }

        // Calculer l'ordre automatiquement
        int ordre = (int) stationRepository.countByExamenId(examenId) + 1;

        Station station = Station.builder()
                .nom(request.getNom())
                .type(request.getType())
                .description(request.getDescription())
                .ordre(ordre)
                .examen(examen)
                .build();

        Station sauvegardee = stationRepository.save(station);
        log.info("Station '{}' ajoutée à l'examen {} (ordre {})", request.getNom(), examenId, ordre);
        return toResponse(sauvegardee, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StationResponse> listerParExamen(Long examenId, Pageable pageable) {
        if (!examenRepository.existsById(examenId)) {
            throw new ResourceNotFoundException("Examen", examenId);
        }
        return stationRepository.findByExamenIdOrderByOrdreAsc(examenId, pageable)
                .map(s -> toResponse(s, false));
    }

    @Override
    @Transactional(readOnly = true)
    public StationResponse trouverParId(Long id) {
        Station station = stationRepository.findByIdWithGrille(id)
                .orElseThrow(() -> new ResourceNotFoundException("Station", id));
        return toResponse(station, true);
    }

    @Override
    public StationResponse modifier(Long id, StationRequest request) {
        Station station = trouverEntite(id);

        // Règle : interdire modification si examen EN_COURS ou plus
        if (!station.getExamen().isGrilleModifiable()) {
            throw new BusinessException(
                    "Impossible de modifier la station : l'examen est au statut "
                            + station.getExamen().getStatut()
            );
        }

        // Vérifier doublon de nom (hors la station elle-même)
        boolean doublonNom = stationRepository.existsByNomAndExamenId(
                request.getNom(), station.getExamen().getId())
                && !station.getNom().equals(request.getNom());

        if (doublonNom) {
            throw new BusinessException(
                    "Une station nommée '" + request.getNom() + "' existe déjà dans cet examen"
            );
        }

        station.setNom(request.getNom());
        station.setType(request.getType());
        station.setDescription(request.getDescription());

        return toResponse(stationRepository.save(station), false);
    }

    @Override
    public void supprimer(Long id) {
        Station station = trouverEntite(id);

        if (!station.getExamen().isGrilleModifiable()) {
            throw new BusinessException(
                    "Impossible de supprimer la station : l'examen est au statut "
                            + station.getExamen().getStatut()
            );
        }

        stationRepository.delete(station);

        // Réordonner les stations restantes
        List<Station> restantes = stationRepository
                .findByExamenIdOrderByOrdreAsc(station.getExamen().getId());
        for (int i = 0; i < restantes.size(); i++) {
            restantes.get(i).setOrdre(i + 1);
        }
        stationRepository.saveAll(restantes);
        log.info("Station {} supprimée. Ordre des stations restantes recalculé.", id);
    }


    // MÉTHODES PRIVÉES

    private Station trouverEntite(Long id) {
        return stationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Station", id));
    }

    private StationResponse toResponse(Station station, boolean avecGrille) {
        StationResponse response = new StationResponse();
        response.setId(station.getId());
        response.setNom(station.getNom());
        response.setType(station.getType());
        response.setOrdre(station.getOrdre());
        response.setDescription(station.getDescription());
        response.setExamenId(station.getExamen().getId());
        response.setHasGrille(station.hasGrille());
        response.setCreatedAt(station.getCreatedAt());
        // La grille est chargée séparément via GrilleService
        return response;
    }
}
