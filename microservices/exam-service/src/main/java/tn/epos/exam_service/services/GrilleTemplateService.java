package tn.epos.exam_service.services;

import tn.epos.exam_service.dto.request.GrilleTemplateRequest;
import tn.epos.exam_service.dto.response.ExamenExportResponse;
import tn.epos.exam_service.dto.response.GrilleTemplateResponse;
import java.util.List;

public interface GrilleTemplateService {
    GrilleTemplateResponse sauvegarderDepuisGrille(Long grilleId, String nomTemplate);
    GrilleTemplateResponse creer(GrilleTemplateRequest request);
    GrilleTemplateResponse trouverParId(Long id);
    List<GrilleTemplateResponse> listerTous();
    void supprimer(Long id);
    void appliquerSurStation(Long templateId, Long stationId);
    ExamenExportResponse exporterExamen(Long examenId);
    Long dupliquerExamen(Long examenId, String nouveauNom);
    void importerGrilleJson(Long stationId, String grilleJson);
}
