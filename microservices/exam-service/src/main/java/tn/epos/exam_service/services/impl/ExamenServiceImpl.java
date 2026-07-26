package tn.epos.exam_service.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.epos.exam_service.dto.request.ExamenRequest;
import tn.epos.exam_service.dto.response.ConflitEvaluateurResponse;
import tn.epos.exam_service.dto.response.ExamTimingResponse;
import tn.epos.exam_service.dto.response.ExamenResponse;
import tn.epos.exam_service.dto.response.StationResponse;
import tn.epos.exam_service.entities.Examen;
import tn.epos.exam_service.entities.Station;
import tn.epos.exam_service.enums.StatutExamen;
import tn.epos.exam_service.config.MatiereAccessChecker;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.exam_service.repositories.ExamenRepository;
import tn.epos.exam_service.services.ExamenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ExamenServiceImpl implements ExamenService {
    private final ExamenRepository examenRepository;
    private final MatiereAccessChecker matiereAccessChecker;
    private final Clock clock;

    @Value("${epos.upload.dir}")
    private String uploadDir;

    // crud
    @Override
    public ExamenResponse creer(ExamenRequest request) {
        log.info("Création d'un examen : {} - matiere_id={}", request.getNom(), request.getMatiereId());

        Examen examen = Examen.builder()
                .nom(request.getNom())
                .matiereId(request.getMatiereId())
                .dateExamen(request.getDateExamen())
                .heureDebut(request.getHeureDebut())
                .dureeStationMin(request.getDureeStationMin())
                .nbEtudiantsParStation(request.getNbEtudiantsParStation())
                .tempsBattementMin(request.getTempsBattementMin())
                .avertissementLeadSec(request.getAvertissementLeadSec())
                .description(request.getDescription())
                .statut(StatutExamen.BROUILLON)
                .build();

        Examen sauvegarde = examenRepository.save(examen);
        log.info("Examen créé avec l'id : {}", sauvegarde.getId());
        return toResponse(sauvegarde, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ExamenResponse> listerTous(Pageable pageable) {
        if (matiereAccessChecker.isUnrestricted()) {
            return examenRepository.findAll(pageable).map(e -> toResponse(e, false));
        }
        Set<Long> scope = matiereAccessChecker.getAccessibleMatiereIds();
        if (scope.isEmpty()) {
            return Page.empty(pageable);
        }
        return examenRepository.findByMatiereIdIn(scope, pageable)
                .map(e -> toResponse(e, false));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ExamenResponse> listerParStatut(StatutExamen statut, Pageable pageable) {
        if (matiereAccessChecker.isUnrestricted()) {
            return examenRepository.findByStatut(statut, pageable).map(e -> toResponse(e, false));
        }
        Set<Long> scope = matiereAccessChecker.getAccessibleMatiereIds();
        if (scope.isEmpty()) {
            return Page.empty(pageable);
        }
        return examenRepository.findByMatiereIdInAndStatut(scope, statut, pageable)
                .map(e -> toResponse(e, false));
    }

    @Override
    @Transactional(readOnly = true)
    public ExamenResponse trouverParId(Long id) {
        Examen examen = examenRepository.findByIdWithStations(id)
                .orElseThrow(() -> new ResourceNotFoundException("Examen", id));
        matiereAccessChecker.checkAccess(examen.getMatiereId());
        return toResponse(examen, true); // true = inclure les stations
    }

    /**
     * État d'exécution seul — lisible par l'ÉVALUATEUR (via {@code checkReadAccess}, comme
     * la lecture de grille), contrairement à {@link #trouverParId(Long)} qui exige un droit
     * d'écriture et renvoie 403 à un évaluateur.
     *
     * <p>C'est la cause racine de deux bugs : scoring appelait {@code GET /api/examens/{id}}
     * avec le jeton de l'évaluateur, se prenait un 403, l'avalait dans un repli « neutre »
     * (statut = null) — et le dashboard évaluateur se vidait entièrement.
     */
    @Override
    @Transactional(readOnly = true)
    public ExamTimingResponse getTiming(Long id) {
        Examen examen = examenRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Examen", id));
        matiereAccessChecker.checkReadAccess(examen.getMatiereId());
        return ExamTimingResponse.builder()
                .id(examen.getId())
                .statut(examen.getStatut())
                .enPause(Boolean.TRUE.equals(examen.getEnPause()))
                .pausedAt(examen.getPausedAt())
                .totalPauseSec(examen.getTotalPauseSec())
                .launchedAt(examen.getLaunchedAt())
                .dureeStationMin(examen.getDureeStationMin())
                .avertissementLeadSec(examen.getAvertissementLeadSec())
                .build();
    }

    @Override
    public ExamenResponse modifier(Long id, ExamenRequest request) {
        Examen examen = trouverEntite(id);
        // Block "matière hijack": a Responsable cannot reassign an exam from
        // another matière into their scope. Controller already guards the
        // incoming request.matiereId; here we guard the existing entity.
        matiereAccessChecker.checkAccess(examen.getMatiereId());

        if (!examen.isModifiable()) {
            throw new BusinessException(
                    "L'examen ne peut être modifié qu'au statut BROUILLON. Statut actuel : " + examen.getStatut()
            );
        }

        examen.setNom(request.getNom());
        examen.setMatiereId(request.getMatiereId());
        examen.setDateExamen(request.getDateExamen());
        examen.setHeureDebut(request.getHeureDebut());
        examen.setDureeStationMin(request.getDureeStationMin());
        examen.setNbEtudiantsParStation(request.getNbEtudiantsParStation());
        examen.setTempsBattementMin(request.getTempsBattementMin());
        examen.setAvertissementLeadSec(request.getAvertissementLeadSec());
        examen.setDescription(request.getDescription());

        return toResponse(examenRepository.save(examen), false);
    }

    @Override
    public ExamenResponse changerStatut(Long id, StatutExamen nouveauStatut) {
        Examen examen = trouverEntite(id);
        matiereAccessChecker.checkAccess(examen.getMatiereId());
        validerTransitionStatut(examen.getStatut(), nouveauStatut);

        // #265 — un évaluateur est un humain : engagé dans la salle d'un examen
        // EN_COURS, il ne peut pas servir une station d'un second examen lancé en
        // parallèle (le Suivi du second attendrait sa vague indéfiniment). Examens
        // simultanés à évaluateurs DISJOINTS : permis — c'est le partage qui bloque.
        if (nouveauStatut == StatutExamen.EN_COURS) {
            List<ConflitEvaluateurResponse> conflits = calculerConflitsEvaluateurs(examen);
            if (!conflits.isEmpty()) {
                ConflitEvaluateurResponse c = conflits.get(0);
                throw new BusinessException(String.format(
                        "Lancement impossible : %d évaluateur(s) (id %s) sont déjà engagés dans "
                                + "l'examen « %s » actuellement en cours. Attendez sa fin ou "
                                + "affectez d'autres évaluateurs à vos stations.",
                        c.getEvaluateurIds().size(),
                        c.getEvaluateurIds().stream().map(String::valueOf)
                                .collect(Collectors.joining(", ")),
                        c.getExamenNom()));
            }
        }

        // Ne pas clôturer un examen figé en pause : le temps effectif est gelé, donc
        // le "dépassement" (gate de fin côté Suivi) ne peut pas avancer. On force la
        // reprise avant la fin pour un état terminal cohérent (enPause=false).
        if (nouveauStatut == StatutExamen.TERMINE && Boolean.TRUE.equals(examen.getEnPause())) {
            throw new BusinessException(
                    "Reprenez l'examen avant de le terminer (il est actuellement en pause)."
            );
        }

        examen.setStatut(nouveauStatut);

        // ADR-0010 : capter l'instant de lancement réel au passage → EN_COURS.
        // Posé une seule fois ; jamais réécrit (la transition n'autorise CONFIGURE
        // → EN_COURS qu'une fois, mais on garde le garde-fou explicite).
        if (nouveauStatut == StatutExamen.EN_COURS && examen.getLaunchedAt() == null) {
            examen.setLaunchedAt(LocalDateTime.now(clock));
            log.info("Examen {} lancé à {} (launched_at)", id, examen.getLaunchedAt());
        }

        log.info("Examen {} : statut changé {} → {}", id, examen.getStatut(), nouveauStatut);
        return toResponse(examenRepository.save(examen), false);
    }

    /**
     * #265 — les examens EN_COURS qui retiennent des évaluateurs dont cet examen a
     * besoin. Sert la ligne « Évaluateurs disponibles » du pre-flight (#185 : la
     * pré-condition s'affiche AVANT le clic) ; {@link #changerStatut} reste la
     * garde autoritaire — l'état est instantané, seul le refus au lancement fait foi.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ConflitEvaluateurResponse> listerConflitsEvaluateurs(Long id) {
        Examen examen = trouverEntite(id);
        matiereAccessChecker.checkAccess(examen.getMatiereId());
        return calculerConflitsEvaluateurs(examen);
    }

    /**
     * Intersection des évaluateurs de cet examen avec ceux de chaque AUTRE examen
     * EN_COURS. Le contrôle #163 (un évaluateur = une station) s'arrête aux bords
     * d'un examen ; celui-ci couvre le travers inter-examens : un humain ne tient
     * pas deux salles à la fois.
     */
    private List<ConflitEvaluateurResponse> calculerConflitsEvaluateurs(Examen examen) {
        Set<Long> demandes = examen.getStations().stream()
                .flatMap(s -> s.getEvaluateurIds().stream())
                .collect(Collectors.toSet());
        if (demandes.isEmpty()) {
            return List.of();
        }

        return examenRepository.findAllByStatut(StatutExamen.EN_COURS).stream()
                .filter(autre -> !autre.getId().equals(examen.getId()))
                .map(autre -> {
                    List<Long> partages = autre.getStations().stream()
                            .flatMap(s -> s.getEvaluateurIds().stream())
                            .filter(demandes::contains)
                            .distinct()
                            .sorted()
                            .toList();
                    return ConflitEvaluateurResponse.builder()
                            .examenId(autre.getId())
                            .examenNom(autre.getNom())
                            .evaluateurIds(partages)
                            .build();
                })
                .filter(c -> !c.getEvaluateurIds().isEmpty())
                .toList();
    }

    @Override
    public ExamenResponse mettreEnPause(Long id) {
        Examen examen = trouverEntite(id);
        matiereAccessChecker.checkAccess(examen.getMatiereId());

        if (examen.getStatut() != StatutExamen.EN_COURS) {
            throw new BusinessException(
                    "Seul un examen EN_COURS peut être mis en pause. Statut actuel : " + examen.getStatut()
            );
        }
        if (Boolean.TRUE.equals(examen.getEnPause())) {
            throw new BusinessException("L'examen est déjà en pause.");
        }

        examen.setEnPause(true);
        examen.setPausedAt(LocalDateTime.now(clock));
        log.info("Examen {} mis en pause à {}", id, examen.getPausedAt());
        return toResponse(examenRepository.save(examen), false);
    }

    @Override
    public ExamenResponse reprendre(Long id) {
        Examen examen = trouverEntite(id);
        matiereAccessChecker.checkAccess(examen.getMatiereId());

        if (!Boolean.TRUE.equals(examen.getEnPause())) {
            throw new BusinessException("L'examen n'est pas en pause.");
        }

        // Cumule la durée de la pause qui s'achève (bornée à >= 0 par sécurité).
        // Compute over Instants (time-zone-aware) so the duration is well-defined.
        long elapsed = 0;
        if (examen.getPausedAt() != null) {
            Instant pausedInstant = examen.getPausedAt().atZone(clock.getZone()).toInstant();
            elapsed = Math.max(0, Duration.between(pausedInstant, clock.instant()).getSeconds());
        }
        int total = (examen.getTotalPauseSec() != null ? examen.getTotalPauseSec() : 0) + (int) elapsed;

        examen.setTotalPauseSec(total);
        examen.setPausedAt(null);
        examen.setEnPause(false);
        log.info("Examen {} repris ; pause de {}s, cumul {}s", id, elapsed, total);
        return toResponse(examenRepository.save(examen), false);
    }

    @Override
    public ExamenResponse reinitialiser(Long id) {
        Examen examen = trouverEntite(id);
        matiereAccessChecker.checkAccess(examen.getMatiereId());

        // Réinitialiser = « dé-lancer » un examen qu'on vient de lancer par erreur : on
        // ne l'autorise QUE depuis EN_COURS. Un examen TERMINE/ARCHIVE se ré-évalue par le
        // canal réajustement (#135), pas par un retour arrière destructif — à ne pas confondre.
        if (examen.getStatut() != StatutExamen.EN_COURS) {
            throw new BusinessException(
                    "Seul un examen EN_COURS peut être réinitialisé. Statut actuel : " + examen.getStatut());
        }

        // Retour à CONFIGURE. On efface l'instant de lancement (ADR-0010) et TOUT l'état
        // de pause (ADR-0009) pour repartir d'un état propre au prochain lancement. Le
        // planning généré (rotations/groupes) est purgé côté scoring-service par
        // l'orchestration appelante, sous le garde-fou #188 : aucune note n'est jamais
        // détruite (le reset est refusé dès qu'une notation existe).
        examen.setStatut(StatutExamen.CONFIGURE);
        examen.setLaunchedAt(null);
        examen.setPausedAt(null);
        examen.setTotalPauseSec(0);
        examen.setEnPause(false);

        log.info("Examen {} réinitialisé EN_COURS → CONFIGURE (launched_at + pause effacés)", id);
        return toResponse(examenRepository.save(examen), false);
    }

    @Override
    public void supprimer(Long id) {
        Examen examen = trouverEntite(id);
        matiereAccessChecker.checkAccess(examen.getMatiereId());

        if (examen.getStatut() == StatutExamen.EN_COURS
                || examen.getStatut() == StatutExamen.TERMINE
                || examen.getStatut() == StatutExamen.ARCHIVE) {
            throw new BusinessException(
                    "Impossible de supprimer un examen au statut : " + examen.getStatut()
            );
        }

        // Supprimer le fichier PDF si présent
        if (examen.getPdfSujetPath() != null) {
            supprimerFichierPdf(examen.getPdfSujetPath());
        }

        examenRepository.delete(examen);
        log.info("Examen {} supprimé.", id);
    }


    // import pdf
    @Override
    public ExamenResponse importerPdf(Long id, MultipartFile fichier) {
        Examen examen = trouverEntite(id);
        matiereAccessChecker.checkAccess(examen.getMatiereId());

        // Validation du fichier
        if (fichier == null || fichier.isEmpty()) {
            throw new BusinessException("Le fichier PDF est vide ou absent");
        }

        String contentType = fichier.getContentType();
        if (contentType == null || !contentType.equals("application/pdf")) {
            throw new BusinessException("Seuls les fichiers PDF sont acceptés");
        }

        // Supprimer l'ancien PDF si existant
        if (examen.getPdfSujetPath() != null) {
            supprimerFichierPdf(examen.getPdfSujetPath());
        }

        // Générer un nom unique pour éviter les collisions
        String nomFichier = "examen_" + id + "_" + UUID.randomUUID() + ".pdf";
        Path dossierUpload = Paths.get(uploadDir);

        try {
            Files.createDirectories(dossierUpload);
            Path destination = dossierUpload.resolve(nomFichier);
            Files.copy(fichier.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);

            examen.setPdfSujetPath(destination.toString());
            examen.setPdfSujetNom(fichier.getOriginalFilename());

            log.info("PDF importé pour l'examen {} : {}", id, nomFichier);
            return toResponse(examenRepository.save(examen), false);

        } catch (IOException e) {
            log.error("Erreur lors de l'import PDF pour l'examen {}", id, e);
            throw new BusinessException("Erreur lors de l'enregistrement du fichier PDF : " + e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public String obtenirCheminPdf(Long id) {
        Examen examen = trouverEntite(id);
        matiereAccessChecker.checkAccess(examen.getMatiereId());
        if (examen.getPdfSujetPath() == null) {
            throw new ResourceNotFoundException("Aucun PDF importé pour l'examen " + id);
        }
        return examen.getPdfSujetPath();
    }


    // MÉTHODES PRIVÉES

    private Examen trouverEntite(Long id) {
        return examenRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Examen", id));
    }

     // Valide les transitions de statut autorisées.
     // Ordre attendu : BROUILLON → CONFIGURE → EN_COURS → TERMINE → ARCHIVE

    private void validerTransitionStatut(StatutExamen actuel, StatutExamen nouveau) {
        boolean valide = switch (actuel) {
            case BROUILLON  -> nouveau == StatutExamen.CONFIGURE;
            case CONFIGURE  -> nouveau == StatutExamen.EN_COURS || nouveau == StatutExamen.BROUILLON;
            case EN_COURS   -> nouveau == StatutExamen.TERMINE;
            case TERMINE    -> nouveau == StatutExamen.ARCHIVE;
            case ARCHIVE    -> false; // état final
        };

        if (!valide) {
            throw new BusinessException(
                    "Transition de statut invalide : " + actuel + " → " + nouveau
            );
        }
    }

    private void supprimerFichierPdf(String chemin) {
        try {
            Files.deleteIfExists(Paths.get(chemin));
        } catch (IOException e) {
            log.warn("Impossible de supprimer le fichier PDF : {}", chemin);
        }
    }

    // Convertit une entité Examen en DTO de réponse.
    // @param avecStations true = inclure la liste des stations (endpoint détaillé)

    private ExamenResponse toResponse(Examen examen, boolean avecStations) {
        ExamenResponse response = new ExamenResponse();
        response.setId(examen.getId());
        response.setNom(examen.getNom());
        response.setMatiereId(examen.getMatiereId());
        response.setDateExamen(examen.getDateExamen());
        response.setHeureDebut(examen.getHeureDebut());
        response.setDureeStationMin(examen.getDureeStationMin());
        response.setNbEtudiantsParStation(examen.getNbEtudiantsParStation());
        response.setTempsBattementMin(examen.getTempsBattementMin());
        response.setAvertissementLeadSec(examen.getAvertissementLeadSec());
        response.setStatut(examen.getStatut());
        response.setDescription(examen.getDescription());
        response.setHasPdfSujet(examen.getPdfSujetPath() != null);
        response.setPdfSujetNom(examen.getPdfSujetNom());
        response.setEnPause(Boolean.TRUE.equals(examen.getEnPause()));
        response.setPausedAt(examen.getPausedAt());
        response.setTotalPauseSec(examen.getTotalPauseSec());
        response.setLaunchedAt(examen.getLaunchedAt());
        response.setCreatedAt(examen.getCreatedAt());
        response.setUpdatedAt(examen.getUpdatedAt());

        if (avecStations && examen.getStations() != null) {
            List<StationResponse> stationResponses = examen.getStations().stream()
                    .map(this::stationToResponse)
                    .collect(Collectors.toList());
            response.setStations(stationResponses);
        }

        return response;
    }

    private StationResponse stationToResponse(Station station) {
        StationResponse sr = new StationResponse();
        sr.setId(station.getId());
        sr.setNom(station.getNom());
        sr.setType(station.getType());
        sr.setOrdre(station.getOrdre());
        sr.setDescription(station.getDescription());
        sr.setExamenId(station.getExamen().getId());
        sr.setHasGrille(station.hasGrille());
        // Embedded stations must carry their évaluateur list too — the dedicated
        // station endpoints already do (StationServiceImpl.toResponse). Without
        // this, GET /examens/{id}.stations[].evaluateurIds is always null.
        sr.setEvaluateurIds(station.getEvaluateurIds());
        sr.setCreatedAt(station.getCreatedAt());
        return sr;
    }
}
