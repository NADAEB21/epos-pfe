package tn.epos.exam_service.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.epos.exam_service.config.MatiereAccessChecker;
import tn.epos.exam_service.dto.request.StationRequest;
import tn.epos.exam_service.dto.response.StationResponse;
import tn.epos.exam_service.entities.Examen;
import tn.epos.exam_service.entities.Station;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.exam_service.exception.ConflictException;
import tn.epos.exam_service.repositories.ExamenRepository;
import tn.epos.exam_service.repositories.StationRepository;
import tn.epos.exam_service.services.StationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class StationServiceImpl implements StationService {
    private final StationRepository stationRepository;
    private final ExamenRepository examenRepository;
    private final MatiereAccessChecker matiereAccessChecker;

    @Override
    public StationResponse ajouter(Long examenId, StationRequest request) {
        Examen examen = examenRepository.findById(examenId)
                .orElseThrow(() -> new ResourceNotFoundException("Examen", examenId));
        matiereAccessChecker.checkAccess(examen.getMatiereId());

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

        // #163 : un évaluateur ne peut tenir qu'une seule station par examen
        verifierEvaluateurUnique(examenId, request.getEvaluateurIds(), null);

        // Calculer l'ordre automatiquement
        int ordre = (int) stationRepository.countByExamenId(examenId) + 1;

        Station station = Station.builder()
                .nom(request.getNom())
                .type(request.getType())
                .description(request.getDescription())
                .ordre(ordre)
                .examen(examen)
                .evaluateurIds(request.getEvaluateurIds() != null
                        ? request.getEvaluateurIds()
                        : new ArrayList<>())
                .build();

        Station sauvegardee = stationRepository.save(station);
        log.info("Station '{}' ajoutée à l'examen {} (ordre {})", request.getNom(), examenId, ordre);
        return toResponse(sauvegardee, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StationResponse> listerParExamen(Long examenId, Pageable pageable) {
        Examen examen = examenRepository.findById(examenId)
                .orElseThrow(() -> new ResourceNotFoundException("Examen", examenId));
        matiereAccessChecker.checkAccess(examen.getMatiereId());
        return stationRepository.findByExamenIdOrderByOrdreAsc(examenId, pageable)
                .map(s -> toResponse(s, false));
    }

    @Override
    @Transactional(readOnly = true)
    public StationResponse trouverParId(Long id) {
        Station station = stationRepository.findByIdWithGrille(id)
                .orElseThrow(() -> new ResourceNotFoundException("Station", id));
        matiereAccessChecker.checkAccess(station.getExamen().getMatiereId());
        return toResponse(station, true);
    }

    @Override
    public StationResponse modifier(Long id, StationRequest request) {
        Station station = trouverEntite(id);
        matiereAccessChecker.checkAccess(station.getExamen().getMatiereId());

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

        // #163 : conflit d'évaluateur avec une AUTRE station du même examen
        verifierEvaluateurUnique(station.getExamen().getId(), request.getEvaluateurIds(), station.getId());

        station.setNom(request.getNom());
        station.setType(request.getType());
        station.setDescription(request.getDescription());
        if (request.getEvaluateurIds() != null) {
            station.setEvaluateurIds(request.getEvaluateurIds());
        }

        return toResponse(stationRepository.save(station), false);
    }

    @Override
    public StationResponse affecterEvaluateurs(Long stationId, List<Long> evaluateurIds) {
        Station station = trouverEntite(stationId);
        matiereAccessChecker.checkAccess(station.getExamen().getMatiereId());

        if (!station.getExamen().isGrilleModifiable()) {
            throw new BusinessException(
                    "Impossible de modifier les évaluateurs : l'examen est au statut "
                            + station.getExamen().getStatut()
            );
        }

        // #163 : conflit d'évaluateur avec une AUTRE station du même examen
        verifierEvaluateurUnique(station.getExamen().getId(), evaluateurIds, stationId);

        station.setEvaluateurIds(evaluateurIds != null ? evaluateurIds : new ArrayList<>());
        log.info("Station {} : {} évaluateur(s) affecté(s)", stationId, station.getEvaluateurIds().size());
        return toResponse(stationRepository.save(station), false);
    }

    @Override
    public void supprimer(Long id) {
        Station station = trouverEntite(id);
        matiereAccessChecker.checkAccess(station.getExamen().getMatiereId());

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

    /**
     * #163 : règle du superviseur — un évaluateur ne peut tenir qu'UNE seule
     * station par examen. Vérifie qu'aucun des {@code evaluateurIds} demandés
     * n'est déjà affecté à une AUTRE station du même examen ; lève un 409
     * (ConflictException) nommant la station en conflit le cas échéant.
     *
     * @param stationCouranteId station en cours d'édition à exclure du contrôle
     *                          (null lors d'un ajout — aucune station à exclure).
     */
    private void verifierEvaluateurUnique(Long examenId, List<Long> evaluateurIds, Long stationCouranteId) {
        if (evaluateurIds == null || evaluateurIds.isEmpty()) {
            return;
        }
        Set<Long> demandes = new HashSet<>(evaluateurIds);
        for (Station autre : stationRepository.findByExamenIdOrderByOrdreAsc(examenId)) {
            if (stationCouranteId != null && autre.getId().equals(stationCouranteId)) {
                continue;
            }
            for (Long dejaAffecte : autre.getEvaluateurIds()) {
                if (demandes.contains(dejaAffecte)) {
                    throw new ConflictException(
                            "L'évaluateur " + dejaAffecte + " est déjà affecté à la station '"
                                    + autre.getNom() + "' de cet examen. "
                                    + "Un évaluateur ne peut tenir qu'une seule station par examen.");
                }
            }
        }
    }

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
        response.setEvaluateurIds(station.getEvaluateurIds());
        response.setHasGrille(station.hasGrille());
        response.setCreatedAt(station.getCreatedAt());
        // La grille est chargée séparément via GrilleService
        return response;
    }
}
