package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.dto.dashboard.*;
import tn.epos.scoring_service.entities.*;
import tn.epos.scoring_service.repositories.*;
import tn.epos.scoring_service.entities.Rotation;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service d'agrégation pour le dashboard de l'évaluateur (app mobile Flutter).
 *
 * Centralise toute la logique de construction des réponses dashboard.
 * Suit le même pattern que les services existants du scoring-service :
 * injection via @RequiredArgsConstructor, méthodes publiques @Transactional,
 * méthodes privées utilitaires en bas de fichier.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class EvaluateurDashboardService {

    // ── Repositories ─────────────────────────────────────────────────────────

    private final ILotRepository                  lotRepository;
    private final IRotationRepository             rotationRepository;
    private final IRotationAssignmentRepository   rotationAssignmentRepository;
    private final INotationRepository             notationRepository;
    private final INotationItemRepository         notationItemRepository;
    private final IExamenParticipationRepository  participationRepository;

    // ── Formateur heure ──────────────────────────────────────────────────────
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // =========================================================================
    // 1. DASHBOARD COMPLET
    //
    // Construit sessions + stats + planning en un seul appel.
    // Remplace les 3 appels mock de SessionRepositoryImpl Flutter.
    // =========================================================================

    @Transactional(readOnly = true)
    public EvaluateurDashboardResponse buildDashboard(Long evaluateurId) {
        log.debug("Building dashboard for evaluateur {}", evaluateurId);

        List<Lot> lots = lotRepository.findByEvaluateurId(evaluateurId);

        List<SessionResponse>      sessions = buildSessions(lots);
        StatsResponse              stats    = buildStats(sessions, lots);
        List<PlanningCellResponse> planning = buildPlanning(evaluateurId);

        return EvaluateurDashboardResponse.builder()
                .sessions(sessions)
                .stats(stats)
                .planning(planning)
                .build();
    }

    // =========================================================================
    // 2. DÉTAIL D'UN LOT AVEC ÉTUDIANTS
    //
    // Charge le lot + la liste des étudiants via lot.getParticipations().
    // Utilise la relation @OneToMany déjà définie dans Lot.java pour éviter
    // une requête séparée (pas de N+1).
    //
    // Correspond à GradingRepository.getLot(stationId, lotNumero) Flutter.
    // =========================================================================

    @Transactional(readOnly = true)
    public LotDetailResponse getLotDetail(Long stationId, Integer lotNumero, Long evaluateurId) {
        log.debug("getLotDetail stationId={} lotNumero={} evaluateur={}", stationId, lotNumero, evaluateurId);

        // Trouve le lot par évaluateur et numéro de lot
        Lot lot = lotRepository.findByEvaluateurIdAndNumeroLot(evaluateurId, lotNumero)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Lot " + lotNumero + " introuvable pour l'évaluateur " + evaluateurId));

        int totalLots = lotRepository.countByExamenId(lot.getExamenId());

        // Utilise la relation JPA existante dans Lot.java :
        // @OneToMany(mappedBy = "lot") List<ExamenParticipation> participations
        // Pas de requête séparée nécessaire — Hibernate charge via la relation.
        List<ExamenParticipation> participations = lot.getParticipations();

        List<LotDetailResponse.EtudiantLotResponse> etudiants = participations == null
                ? Collections.emptyList()
                : participations.stream()
                .filter(p -> p.getEtudiant() != null)
                .map(p -> LotDetailResponse.EtudiantLotResponse.builder()
                        .id(p.getEtudiant().getId())
                        .nom(p.getEtudiant().getNom())
                        .prenom(p.getEtudiant().getPrenom())
                        .numeroInscription(p.getEtudiant().getNumero_inscription())
                        .numeroEchantillon(parseEchantillon(p.getNum_echantillon()))
                        .build())
                .collect(Collectors.toList());

        return LotDetailResponse.builder()
                .id(lot.getId())
                .numero(lot.getNumeroLot())
                .total(totalLots)
                .valide(lot.getStatut() == LotStatus.TERMINE)
                .etudiants(etudiants)
                .build();
    }

    // =========================================================================
    // 3. SAISIR UNE NOTATION
    //
    // Accepte { etudiantId, stationId, grilleId, itemId, valeur } et gère
    // automatiquement la chaîne : participation → assignment → notation → item.
    // Correspond à GradingRepository.saveNotation(notation) Flutter.
    // =========================================================================

    public void saisirNotation(SaisirNotationRequest request, Long evaluateurId) {
        log.debug("saisirNotation etudiant={} station={} item={} valeur={}",
                request.getEtudiantId(), request.getStationId(),
                request.getItemId(), request.getValeur());

        // 1. Retrouve la participation via la requête JPQL cross-entités
        ExamenParticipation participation =
                participationRepository.findByEtudiantIdAndStationId(
                                request.getEtudiantId(), request.getStationId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Participation introuvable : étudiant=" + request.getEtudiantId()
                                        + " station=" + request.getStationId()));

        // 2. Retrouve ou crée le RotationAssignment
        RotationAssignment assignment =
                rotationAssignmentRepository.findByParticipationId(participation.getId())
                        .orElseGet(() -> createAssignment(participation, request.getStationId(), evaluateurId));

        // 3. Retrouve ou crée la Notation
        Notation notation = notationRepository.findByAssignmentId(assignment.getId())
                .orElseGet(() -> createNotation(assignment, request.getStationId(), request.getGrilleId()));

        if (Boolean.TRUE.equals(notation.getVerouillee())) {
            throw new BusinessException(
                    "Notation verrouillée — impossible de modifier les notes de l'étudiant "
                            + request.getEtudiantId());
        }

        // 4. Upsert : met à jour si l'item existe, crée sinon
        Optional<NotationItem> existingItem =
                notationItemRepository.findByNotationIdAndItemId(
                        notation.getId(), request.getItemId());

        if (existingItem.isPresent()) {
            existingItem.get().setValeur(request.getValeur());
            notationItemRepository.save(existingItem.get());
        } else {
            NotationItem newItem = new NotationItem();
            newItem.setItemId(request.getItemId());
            newItem.setValeur(request.getValeur());
            newItem.setNotation(notation);
            notationItemRepository.save(newItem);
        }

        // 5. Recalcule le score final
        recalculerScoreFinal(notation);
    }

    // =========================================================================
    // 4. VALIDER UN ÉTUDIANT
    //
    // Verrouille la notation d'un étudiant pour une station.
    // Correspond à GradingRepository.validerEtudiant(etudiantId, stationId) Flutter.
    // =========================================================================

    public void validerEtudiant(Long etudiantId, Long stationId, Long evaluateurId) {
        log.debug("validerEtudiant etudiant={} station={}", etudiantId, stationId);

        ExamenParticipation participation =
                participationRepository.findByEtudiantIdAndStationId(etudiantId, stationId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Participation introuvable : étudiant=" + etudiantId
                                        + " station=" + stationId));

        rotationAssignmentRepository.findByParticipationId(participation.getId())
                .ifPresent(assignment ->
                        notationRepository.findByAssignmentId(assignment.getId())
                                .ifPresent(notation -> {
                                    notation.setVerouillee(true);
                                    notationRepository.save(notation);
                                    log.info("Notation verrouillée : étudiant={} station={}",
                                            etudiantId, stationId);
                                }));
    }

    // =========================================================================
    // 5. VALIDER UN LOT
    //
    // Marque le lot comme TERMINE.
    // Correspond à GradingRepository.validerLot(lotId) Flutter.
    // =========================================================================

    public void validerLot(Long lotId, Long evaluateurId) {
        log.debug("validerLot lot={} evaluateur={}", lotId, evaluateurId);

        Lot lot = lotRepository.findById(lotId)
                .orElseThrow(() -> new ResourceNotFoundException("Lot introuvable : " + lotId));

        if (!lot.getEvaluateurId().equals(evaluateurId)) {
            throw new BusinessException(
                    "Accès refusé : le lot " + lotId
                            + " n'appartient pas à l'évaluateur " + evaluateurId);
        }

        lot.setStatut(LotStatus.TERMINE);
        lotRepository.save(lot);
        log.info("Lot {} validé par l'évaluateur {}", lotId, evaluateurId);
    }

    // =========================================================================
    // MÉTHODES PRIVÉES — Construction des réponses
    // =========================================================================

    /**
     * Construit la liste des sessions à partir des lots de l'évaluateur.
     *
     * Correction point 3 : stationId est extrait depuis Rotation.stationId
     * (FK logique vers exam_db.stations, ajoutée en V2).
     * C'est le seul champ qui relie scoring-service à exam-service.
     *
     * Flux :
     *   Lot → groups (StudentGroup) → rotations (Rotation) → stationId
     *
     * Si aucune rotation n'est encore associée au lot (données incomplètes),
     * stationId vaut null et la grille ne sera pas chargeable — cas traité
     * côté Flutter par un message d'erreur explicite.
     */
    private List<SessionResponse> buildSessions(List<Lot> lots) {
        return lots.stream()
                .map(lot -> {
                    int totalLots = lotRepository.countByExamenId(lot.getExamenId());

                    // Extrait stationId depuis les rotations du lot
                    Long stationId = extractStationId(lot);

                    return SessionResponse.builder()
                            .id(lot.getId())
                            .stationId(stationId)                  // ← nouveau champ
                            .stationNom("Station " + (stationId != null ? stationId : lot.getExamenId()))
                            .matiere("Chimie Thérapeutique")
                            .annee("CT-2026")
                            .statut(mapLotStatutToSessionStatut(lot.getStatut()))
                            .heureDebut(getHeureDebutLot(lot))
                            .heureFin(null)
                            .nbEtudiants(lot.getTailleLot() != null ? lot.getTailleLot() : 4)
                            .salle("Salle " + lot.getId())
                            .lotActuel(lot.getNumeroLot() != null ? lot.getNumeroLot() : 0)
                            .totalLots(totalLots)
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * Construit les statistiques agrégées depuis les sessions et les lots.
     */
    private StatsResponse buildStats(List<SessionResponse> sessions, List<Lot> lots) {
        int totalEtudiants = lots.stream()
                .mapToInt(l -> l.getTailleLot() != null ? l.getTailleLot() : 0)
                .sum();

        long lotsValides = lots.stream()
                .filter(l -> l.getStatut() == LotStatus.TERMINE)
                .count();

        return StatsResponse.builder()
                .sessionsAssignees(sessions.size())
                .totalEtudiants(totalEtudiants)
                .lotsValides((int) lotsValides)
                .totalLots(lots.size())
                .build();
    }

    /**
     * Construit le planning du jour (grille heure × lot) depuis les rotations.
     */
    private List<PlanningCellResponse> buildPlanning(Long evaluateurId) {
        List<Rotation> rotations = rotationRepository.findByEvaluateurId(evaluateurId);

        List<PlanningCellResponse> cells = rotations.stream()
                .filter(r -> r.getDebutCreneau() != null)
                .map(r -> PlanningCellResponse.builder()
                        .heure(r.getDebutCreneau().format(TIME_FMT))
                        .lotNumero(getLotNumeroPourRotation(r))
                        .statut(mapRotationStatutToPlanningStatut(r.getStatut()))
                        .build())
                .collect(Collectors.toList());

        cells.sort(Comparator.comparing(PlanningCellResponse::getHeure)
                .thenComparingInt(PlanningCellResponse::getLotNumero));

        return cells;
    }

    // =========================================================================
    // MÉTHODES PRIVÉES — Création d'entités
    // =========================================================================

    private RotationAssignment createAssignment(ExamenParticipation participation,
                                                Long stationId,
                                                Long evaluateurId) {
        Rotation rotation = rotationRepository
                .findByEvaluateurIdAndStationId(evaluateurId, stationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Rotation introuvable : evaluateur=" + evaluateurId
                                + " station=" + stationId));

        RotationAssignment assignment = new RotationAssignment();
        assignment.setRotation(rotation);
        assignment.setParticipation(participation);
        assignment.setPresenceConfirmee(true);
        assignment.setTempsAdditionnel(0);
        return rotationAssignmentRepository.save(assignment);
    }

    private Notation createNotation(RotationAssignment assignment, Long stationId, Long grilleId) {
        Notation notation = new Notation();
        notation.setAssignment(assignment);
        notation.setStationId(stationId);
        notation.setGrilleId(grilleId);
        notation.setScore_final(0.0f);
        notation.setVerouillee(false);
        notation.setIs_synced(false);
        return notationRepository.save(notation);
    }

    private void recalculerScoreFinal(Notation notation) {
        List<NotationItem> items = notationItemRepository.findByNotationId(notation.getId());
        float score = (float) items.stream()
                .mapToDouble(i -> i.getValeur() != null ? i.getValeur() : 0f)
                .sum();
        notation.setScore_final(score);
        notationRepository.save(notation);
    }

    // =========================================================================
    // MÉTHODES PRIVÉES — Mapping et utilitaires
    // =========================================================================

    private String mapLotStatutToSessionStatut(LotStatus statut) {
        if (statut == null) return "A_VENIR";
        return switch (statut) {
            case EN_COURS   -> "EN_COURS";
            case TERMINE    -> "TERMINEE";
            case EN_ATTENTE -> "A_VENIR";
        };
    }

    private String mapRotationStatutToPlanningStatut(RotationStatus statut) {
        if (statut == null) return "AUCUN";
        return switch (statut) {
            case TERMINE              -> "TERMINE";
            case EN_COURS, EN_ATTENTE -> "A_VENIR";
        };
    }

    private String getHeureDebutLot(Lot lot) {
        if (lot.getGroups() == null || lot.getGroups().isEmpty()) return "00:00";
        return lot.getGroups().stream()
                .flatMap(g -> g.getRotations() == null
                        ? java.util.stream.Stream.empty()
                        : g.getRotations().stream())
                .filter(r -> r.getDebutCreneau() != null)
                .map(r -> r.getDebutCreneau().format(TIME_FMT))
                .findFirst()
                .orElse("00:00");
    }

    private int getLotNumeroPourRotation(Rotation rotation) {
        if (rotation.getStudentGroup() == null) return 0;
        if (rotation.getStudentGroup().getLot() == null) return 0;
        Integer num = rotation.getStudentGroup().getLot().getNumeroLot();
        return num != null ? num : 0;
    }

    private Integer parseEchantillon(String numEchantillon) {
        if (numEchantillon == null || numEchantillon.isBlank()) return null;
        try {
            return Integer.parseInt(numEchantillon.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Remonte la chaîne Lot → groups → rotations pour extraire stationId.
     * Retourne null si aucune rotation n'est encore assignée.
     */
    private Long extractStationId(Lot lot) {
        if (lot.getGroups() == null || lot.getGroups().isEmpty()) return null;
        return lot.getGroups().stream()
                .flatMap(g -> g.getRotations() == null
                        ? java.util.stream.Stream.empty()
                        : g.getRotations().stream())
                .filter(r -> r.getStationId() != null)
                .map(Rotation::getStationId)
                .findFirst()
                .orElse(null);
    }
}