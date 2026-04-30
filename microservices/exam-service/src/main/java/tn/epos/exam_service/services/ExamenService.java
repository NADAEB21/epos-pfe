package tn.epos.exam_service.services;

import tn.epos.exam_service.dto.request.ExamenRequest;
import tn.epos.exam_service.dto.response.ExamenResponse;
import tn.epos.exam_service.enums.StatutExamen;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
public interface ExamenService {
    /** Créer un nouvel examen (statut initial : BROUILLON) */
    ExamenResponse creer(ExamenRequest request);

    /** Récupérer tous les examens (liste légère, sans stations) */
    List<ExamenResponse> listerTous();

    /** Récupérer les examens filtrés par statut */
    List<ExamenResponse> listerParStatut(StatutExamen statut);

    /** Récupérer un examen par ID avec ses stations */
    ExamenResponse trouverParId(Long id);

    /** Modifier un examen (uniquement si statut BROUILLON) */
    ExamenResponse modifier(Long id, ExamenRequest request);

    /** Changer le statut d'un examen (BROUILLON → CONFIGURE → EN_COURS...) */
    ExamenResponse changerStatut(Long id, StatutExamen nouveauStatut);

    /** Supprimer un examen (uniquement si statut BROUILLON ou CONFIGURE) */
    void supprimer(Long id);

    /** Importer le PDF du sujet d'examen */
    ExamenResponse importerPdf(Long id, MultipartFile fichier);

    /** Télécharger le PDF du sujet (retourne le chemin local) */
    String obtenirCheminPdf(Long id);
}
