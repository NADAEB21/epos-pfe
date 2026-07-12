package tn.epos.exam_service.services;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.epos.exam_service.dto.request.ExamenRequest;
import tn.epos.exam_service.dto.response.ExamenResponse;
import tn.epos.exam_service.enums.StatutExamen;
import org.springframework.web.multipart.MultipartFile;

public interface ExamenService {
    /** Créer un nouvel examen (statut initial : BROUILLON) */
    ExamenResponse creer(ExamenRequest request);

    /** Récupérer tous les examens (liste légère, sans stations) */
    Page<ExamenResponse> listerTous(Pageable pageable);

    /** Récupérer les examens filtrés par statut */
    Page<ExamenResponse> listerParStatut(StatutExamen statut, Pageable pageable);

    /** Récupérer un examen par ID avec ses stations */
    ExamenResponse trouverParId(Long id);

    /** Modifier un examen (uniquement si statut BROUILLON) */
    ExamenResponse modifier(Long id, ExamenRequest request);

    /** Changer le statut d'un examen (BROUILLON → CONFIGURE → EN_COURS...) */
    ExamenResponse changerStatut(Long id, StatutExamen nouveauStatut);

    /** Mettre en pause un examen EN_COURS (ADR-0009 ; le statut reste EN_COURS) */
    ExamenResponse mettreEnPause(Long id);

    /** Reprendre un examen en pause (cumule la durée de pause écoulée) */
    ExamenResponse reprendre(Long id);

    /** Supprimer un examen (uniquement si statut BROUILLON ou CONFIGURE) */
    void supprimer(Long id);

    /** Importer le PDF du sujet d'examen */
    ExamenResponse importerPdf(Long id, MultipartFile fichier);

    /** Télécharger le PDF du sujet (retourne le chemin local) */
    String obtenirCheminPdf(Long id);
}
