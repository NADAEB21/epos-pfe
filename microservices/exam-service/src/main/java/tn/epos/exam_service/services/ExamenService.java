package tn.epos.exam_service.services;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.epos.exam_service.dto.request.ExamenRequest;
import tn.epos.exam_service.dto.response.ExamTimingResponse;
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

    /**
     * État d'exécution d'un examen (statut / pause / durée), lisible aussi par un ÉVALUATEUR.
     *
     * <p>{@link #trouverParId(Long)} passe par {@code checkAccess} (écriture) et rejette donc
     * un évaluateur en 403. Le dashboard évaluateur a pourtant besoin du statut de l'examen —
     * d'où cette lecture dédiée, qui utilise {@code checkReadAccess} (le même contrôle que la
     * lecture de grille, déjà autorisée à l'évaluateur).
     */
    ExamTimingResponse getTiming(Long id);

    /** Modifier un examen (uniquement si statut BROUILLON) */
    ExamenResponse modifier(Long id, ExamenRequest request);

    /** Changer le statut d'un examen (BROUILLON → CONFIGURE → EN_COURS...) */
    ExamenResponse changerStatut(Long id, StatutExamen nouveauStatut);

    /**
     * #265 — les examens EN_COURS qui partagent des évaluateurs avec celui-ci.
     * Alimente la ligne « Évaluateurs disponibles » du pre-flight de Lancement ;
     * la garde autoritaire reste {@link #changerStatut} (refus au lancement).
     */
    java.util.List<tn.epos.exam_service.dto.response.ConflitEvaluateurResponse> listerConflitsEvaluateurs(Long id);

    /** Mettre en pause un examen EN_COURS (ADR-0009 ; le statut reste EN_COURS) */
    ExamenResponse mettreEnPause(Long id);

    /** Reprendre un examen en pause (cumule la durée de pause écoulée) */
    ExamenResponse reprendre(Long id);

    /**
     * Réinitialiser un examen EN_COURS vers CONFIGURE (#183 — « dé-lancer »).
     *
     * <p>Récupération le jour de l'examen : on a lancé, rien ne s'est encore passé,
     * on revient à un état configurable. Efface l'horodatage de lancement (ADR-0010)
     * et tout l'état de pause (ADR-0009). Le nettoyage du planning généré
     * (rotations / groupes) vit dans scoring-service et est orchestré séparément,
     * sous le garde-fou #188 (refus si une notation existe).
     */
    ExamenResponse reinitialiser(Long id);

    /** Supprimer un examen (uniquement si statut BROUILLON ou CONFIGURE) */
    void supprimer(Long id);

    /** Importer le PDF du sujet d'examen */
    ExamenResponse importerPdf(Long id, MultipartFile fichier);

    /** Télécharger le PDF du sujet (retourne le chemin local) */
    String obtenirCheminPdf(Long id);
}
