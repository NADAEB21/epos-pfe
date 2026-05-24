package tn.epos.exam_service.services;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.epos.exam_service.dto.request.StationRequest;
import tn.epos.exam_service.dto.response.StationResponse;

import java.util.List;

public interface StationService {
    /** Ajouter une station à un examen */
    StationResponse ajouter(Long examenId, StationRequest request);

    /** Lister toutes les stations d'un examen */
    Page<StationResponse> listerParExamen(Long examenId, Pageable pageable);

    /** Récupérer une station avec sa grille */
    StationResponse trouverParId(Long id);

    /** Modifier une station */
    StationResponse modifier(Long id, StationRequest request);

    StationResponse affecterEvaluateurs(Long stationId, List<Long> evaluateurIds);

    /** Supprimer une station (et sa grille en cascade) */
    void supprimer(Long id);
}
