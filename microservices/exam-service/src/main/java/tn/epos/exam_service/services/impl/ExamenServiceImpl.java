package tn.epos.exam_service.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tn.epos.exam_service.dto.request.ExamenRequest;
import tn.epos.exam_service.dto.response.ExamenResponse;
import tn.epos.exam_service.dto.response.StationResponse;
import tn.epos.exam_service.entities.Examen;
import tn.epos.exam_service.entities.Station;
import tn.epos.exam_service.enums.StatutExamen;
import tn.epos.exam_service.config.MatiereAccessChecker;
import tn.epos.exam_service.exception.BusinessException;
import tn.epos.exam_service.exception.ResourceNotFoundException;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ExamenServiceImpl implements ExamenService {
    private final ExamenRepository examenRepository;
    private final MatiereAccessChecker matiereAccessChecker;

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
                .dureeStationMin(request.getDureeStationMin())
                .nbEtudiantsParStation(request.getNbEtudiantsParStation())
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
        return examenRepository.findAll(pageable)
                .map(e -> toResponse(e, false));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ExamenResponse> listerParStatut(StatutExamen statut, Pageable pageable) {
        return examenRepository.findByStatut(statut, pageable)
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
        examen.setDureeStationMin(request.getDureeStationMin());
        examen.setNbEtudiantsParStation(request.getNbEtudiantsParStation());
        examen.setDescription(request.getDescription());

        return toResponse(examenRepository.save(examen), false);
    }

    @Override
    public ExamenResponse changerStatut(Long id, StatutExamen nouveauStatut) {
        Examen examen = trouverEntite(id);
        matiereAccessChecker.checkAccess(examen.getMatiereId());
        validerTransitionStatut(examen.getStatut(), nouveauStatut);
        examen.setStatut(nouveauStatut);
        log.info("Examen {} : statut changé {} → {}", id, examen.getStatut(), nouveauStatut);
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
        response.setDureeStationMin(examen.getDureeStationMin());
        response.setNbEtudiantsParStation(examen.getNbEtudiantsParStation());
        response.setStatut(examen.getStatut());
        response.setDescription(examen.getDescription());
        response.setHasPdfSujet(examen.getPdfSujetPath() != null);
        response.setPdfSujetNom(examen.getPdfSujetNom());
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
        sr.setCreatedAt(station.getCreatedAt());
        return sr;
    }
}
