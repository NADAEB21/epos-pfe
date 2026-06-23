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
import tn.epos.scoring_service.client.ExamServiceClient;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service d'agrégation pour le dashboard de l'évaluateur (app mobile Flutter).
 *
 * Corrections apportées :
 *   - FIX 1 : saisirNotation() échouait silencieusement quand
 *             findByEtudiantIdAndStationId() ne trouvait pas la participation
 *             (JPQL navigue via lot.groups.rotations → la chaîne doit être complète).
 *             Ajout d'une vérification explicite et d'un log détaillé.
 *
 *   - FIX 2 : validerLot() met maintenant aussi à jour Rotation.statut = TERMINE
 *             pour que resolveSessionStatut() retourne "TERMINEE" immédiatement
 *             après la validation, sans attendre l'expiration du timer horaire.
 *
 *   - FIX 3 : resolveSessionStatut() — la fenêtre temporelle de détection
 *             EN_COURS est étendue à debut + dureeStation + 30min de grâce,
 *             pour couvrir les décalages réels entre évaluateurs.
 *             La session passe TERMINEE uniquement si :
 *               a) lot validé manuellement (Lot.statut == TERMINE), OU
 *               b) rotation marquée TERMINE (via validerLot), OU
 *               c) on est au-delà de debut + dureeStation + GRACE_PERIOD_MIN.
 *
 *   - SonarQube S1192 : les littéraux répétés sont extraits en constantes.
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
    private final ExamServiceClient               examServiceClient;

    // ── Constantes ────────────────────────────────────────────────────────────

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /** Statut renvoyé au Flutter pour une session terminée (S1192). */
    private static final String STATUT_TERMINEE = "TERMINEE";

    /** Fragment de log réutilisé pour tracer etudiantId + stationId (S1192). */
    private static final String LOG_ETUDIANT_STATION = " station=";

    /**
     * Durée nominale d'une station en minutes (valeur par défaut du cahier des charges).
     * Idéalement lue depuis Examen.dureeStationMin via exam-service — hardcodée ici
     * pour éviter un appel cross-service à chaque tick dashboard.
     */
    private static final int DUREE_STATION_MIN = 15;

    /**
     * FIX 3 — Période de grâce après la fin théorique d'une station.
     * La session reste EN_COURS pendant 30 minutes supplémentaires pour absorber
     * les décalages entre évaluateurs, les démarrages en retard, etc.
     */
    private static final int GRACE_PERIOD_MIN = 30;

    // =========================================================================
    // 1. DASHBOARD COMPLET
    // =========================================================================

    @Transactional(readOnly = true)
    public EvaluateurDashboardResponse buildDashboard(Long evaluateurId) {
        log.debug("Building dashboard for evaluateur {}", evaluateurId);

        List<Rotation> rotations = rotationRepository.findByEvaluateurId(evaluateurId);
        List<Lot>      lots      = lotRepository.findByEvaluateurId(evaluateurId);

        List<SessionResponse>      sessions = buildSessions(rotations, lots);
        StatsResponse              stats    = buildStats(sessions, lots);
        List<PlanningCellResponse> planning = buildPlanning(rotations);

        return EvaluateurDashboardResponse.builder()
                .sessions(sessions)
                .stats(stats)
                .planning(planning)
                .build();
    }

    // =========================================================================
    // 2. DÉTAIL D'UN LOT AVEC ÉTUDIANTS
    // =========================================================================

    @Transactional(readOnly = true)
    public LotDetailResponse getLotDetail(Long stationId, Integer lotNumero, Long evaluateurId) {
        log.debug("getLotDetail stationId={} lotNumero={} evaluateur={}",
                stationId, lotNumero, evaluateurId);

        Lot lot = lotRepository.findByEvaluateurIdAndNumeroLot(evaluateurId, lotNumero)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Lot " + lotNumero + " introuvable pour l'évaluateur " + evaluateurId));

        int totalLots = lotRepository.countByExamenId(lot.getExamenId());

        List<ExamenParticipation> participations =
                participationRepository.findByLotId(lot.getId());

        List<LotDetailResponse.EtudiantLotResponse> etudiants = participations.stream()
                .filter(p -> p.getEtudiant() != null)
                .map(p -> LotDetailResponse.EtudiantLotResponse.builder()
                        .id(p.getEtudiant().getId())
                        .nom(p.getEtudiant().getNom())
                        .prenom(p.getEtudiant().getPrenom())
                        .numeroInscription(p.getEtudiant().getNumero_inscription())
                        .numeroEchantillon(parseEchantillon(p.getNum_echantillon()))
                        .absent(!Boolean.TRUE.equals(p.getEst_present()))
                        .verrouille(isNotationVerrouillée(p.getId()))
                        .commentaire(p.getCommentaire())
                        .notationItems(loadNotationItems(p.getId()))
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

    /** Charge les NotationItems existants pour une participation donnée. */
    private List<LotDetailResponse.NotationItemResponse> loadNotationItems(Long participationId) {
        return rotationAssignmentRepository.findByParticipationId(participationId)
                .flatMap(a -> notationRepository.findByAssignmentId(a.getId()))
                .map(n -> notationItemRepository.findByNotationId(n.getId()).stream()
                        .map(ni -> LotDetailResponse.NotationItemResponse.builder()
                                .itemId(ni.getItemId())
                                .valeur(ni.getValeur())
                                .build())
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());
    }

    // =========================================================================
    // 3. SAISIR UNE NOTATION
    // =========================================================================

    public void saisirNotation(SaisirNotationRequest request, Long evaluateurId) {
        log.debug("saisirNotation etudiant={} station={} grille={} item={} valeur={}",
                request.getEtudiantId(), request.getStationId(),
                request.getGrilleId(), request.getItemId(), request.getValeur());

        // FIX 1 — La requête JPQL findByEtudiantIdAndStationId navigue via
        //   ExamenParticipation → lot → groups (StudentGroup) → rotations → stationId.
        // Si la chaîne StudentGroup → Rotation n'est pas complète en base,
        // la requête renvoie empty et la notation n'est jamais persistée.
        ExamenParticipation participation =
                participationRepository.findByEtudiantIdAndStationId(
                                request.getEtudiantId(), request.getStationId())
                        .orElseThrow(() -> {
                            log.error(
                                    "Participation introuvable — vérifiez que la chaîne " +
                                            "ExamenParticipation→Lot→StudentGroup→Rotation est complète. " +
                                            "etudiantId={}" + LOG_ETUDIANT_STATION + "{}",
                                    request.getEtudiantId(), request.getStationId());
                            return new ResourceNotFoundException(
                                    "Participation introuvable : étudiant=" + request.getEtudiantId()
                                            + LOG_ETUDIANT_STATION + request.getStationId()
                                            + ". Assurez-vous que l'étudiant est lié à un lot "
                                            + "avec un StudentGroup ayant une Rotation sur cette station.");
                        });

        RotationAssignment assignment =
                rotationAssignmentRepository.findByParticipationId(participation.getId())
                        .orElseGet(() -> createAssignment(
                                participation, request.getStationId(), evaluateurId));

        Notation notation = notationRepository.findByAssignmentId(assignment.getId())
                .orElseGet(() -> createNotation(
                        assignment, request.getStationId(), request.getGrilleId()));

        if (Boolean.TRUE.equals(notation.getVerouillee())) {
            throw new BusinessException(
                    "Notation verrouillée — impossible de modifier les notes de l'étudiant "
                            + request.getEtudiantId());
        }

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

        recalculerScoreFinal(notation);
        log.debug("NotationItem sauvegardé — notation={} item={} valeur={}",
                notation.getId(), request.getItemId(), request.getValeur());
    }

    // =========================================================================
    // 4. VALIDER UN ÉTUDIANT
    // =========================================================================

    public void validerEtudiant(Long etudiantId, Long stationId,
                                Long evaluateurId, ValiderEtudiantRequest request) {
        log.debug("validerEtudiant etudiant={} station={} absent={}",
                etudiantId, stationId, request.isAbsent());

        ExamenParticipation participation =
                participationRepository.findByEtudiantIdAndStationId(etudiantId, stationId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Participation introuvable : étudiant=" + etudiantId
                                        + LOG_ETUDIANT_STATION + stationId));

        RotationAssignment assignment =
                rotationAssignmentRepository.findByParticipationId(participation.getId())
                        .orElseGet(() -> createAssignment(participation, stationId, evaluateurId));

        Notation notation = notationRepository.findByAssignmentId(assignment.getId())
                .orElseGet(() -> createNotation(assignment, stationId, request.getGrilleId()));

        if (request.isAbsent()) {
            List<NotationItem> items =
                    notationItemRepository.findByNotationId(notation.getId());
            if (!items.isEmpty()) {
                notationItemRepository.deleteAll(items);
            }
            notation.setScore_final(0.0f);
        }

        notation.setVerouillee(true);
        notationRepository.save(notation);

        participation.setEst_present(!request.isAbsent());
        if (request.getCommentaire() != null && !request.getCommentaire().isBlank()) {
            participation.setCommentaire(request.getCommentaire());
        }
        participation.setNote(notation.getScore_final());
        participationRepository.save(participation);

        log.info("Étudiant {} {} à la station {} — note={} (évaluateur {})",
                etudiantId,
                request.isAbsent() ? "ABSENT" : "validé",
                stationId, notation.getScore_final(), evaluateurId);
    }

    // =========================================================================
    // 5. VALIDER UN LOT
    // =========================================================================

    /**
     * FIX 2 — validerLot() marque maintenant :
     *   a) Lot.statut = TERMINE
     *   b) Rotation.statut = TERMINE pour toutes les rotations liées au lot
     *      via StudentGroup.
     */
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

        if (lot.getGroups() != null) {
            lot.getGroups().forEach(group -> {
                if (group.getRotations() != null) {
                    group.getRotations().forEach(rotation -> {
                        if (rotation.getStatut() != RotationStatus.TERMINE) {
                            rotation.setStatut(RotationStatus.TERMINE);
                            rotationRepository.save(rotation);
                            log.debug("Rotation {} marquée TERMINE (lot {})",
                                    rotation.getId(), lotId);
                        }
                    });
                }
            });
        }

        log.info("Lot {} validé et rotations associées marquées TERMINE (évaluateur {})",
                lotId, evaluateurId);
    }

    // =========================================================================
    // MÉTHODES PRIVÉES — Construction des réponses
    // =========================================================================

    private List<SessionResponse> buildSessions(List<Rotation> rotations, List<Lot> lots) {
        LocalDateTime maintenant = LocalDateTime.now(ZoneId.of("Africa/Tunis"));

        return rotations.stream()
                .filter(r -> r.getDebutCreneau() != null && r.getStationId() != null)
                .map(rotation -> {
                    String statut     = resolveSessionStatut(rotation, maintenant);
                    String heureDebut = rotation.getDebutCreneau().format(TIME_FMT);
                    String heureFin   = rotation.getDebutCreneau()
                            .plusMinutes(DUREE_STATION_MIN)
                            .format(TIME_FMT);

                    Lot lotLie = resolverLotDepuisRotation(rotation, lots);

                    int nbEtudiants = (lotLie != null && lotLie.getTailleLot() != null)
                            ? lotLie.getTailleLot() : 0;
                    int lotActuel   = (lotLie != null && lotLie.getNumeroLot() != null)
                            ? lotLie.getNumeroLot() : 0;
                    int totalLots   = (lotLie != null)
                            ? lotRepository.countByExamenId(lotLie.getExamenId()) : 0;

                    ExamServiceClient.StationInfo info =
                            examServiceClient.getStationInfo(rotation.getStationId());

                    return SessionResponse.builder()
                            .id(lotLie != null ? lotLie.getId() : rotation.getId())
                            .stationId(rotation.getStationId())
                            .stationNom(info.nom())
                            .matiere("Chimie Thérapeutique")
                            .annee("CT-" + rotation.getDebutCreneau().getYear())
                            .statut(statut)
                            .heureDebut(heureDebut)
                            .heureFin(STATUT_TERMINEE.equals(statut) ? heureFin : null)
                            .nbEtudiants(nbEtudiants)
                            .salle("Salle " + rotation.getStationId())
                            .lotActuel(lotActuel)
                            .totalLots(totalLots)
                            .build();
                })
                .sorted(Comparator
                        .comparingInt((SessionResponse s) -> sessionStatutOrdre(s.getStatut()))
                        .thenComparing(SessionResponse::getHeureDebut))
                .collect(Collectors.toList());
    }

    /**
     * FIX 3 — Détermine le statut d'une session avec période de grâce.
     *
     * Priorité (ordre strict) :
     *   1. Rotation.statut == TERMINE → TERMINEE  (validation manuelle via validerLot)
     *   2. Lot lié TERMINE            → TERMINEE  (validation manuelle via validerLot)
     *   3. debutCreneau > maintenant  → A_VENIR
     *   4. maintenant ≤ debut + duree + grace  → EN_COURS
     *   5. maintenant > debut + duree + grace  → TERMINEE (expiration automatique)
     */
    private String resolveSessionStatut(Rotation rotation, LocalDateTime maintenant) {
        if (rotation.getStatut() == RotationStatus.TERMINE) {
            return STATUT_TERMINEE;
        }

        if (rotation.getStudentGroup() != null
                && rotation.getStudentGroup().getLot() != null
                && rotation.getStudentGroup().getLot().getStatut() == LotStatus.TERMINE) {
            return STATUT_TERMINEE;
        }

        LocalDateTime debut     = rotation.getDebutCreneau();
        LocalDateTime finReelle = debut.plusMinutes((long) DUREE_STATION_MIN + GRACE_PERIOD_MIN);

        if (maintenant.isBefore(debut)) {
            return "A_VENIR";
        }

        if (!maintenant.isAfter(finReelle)) {
            return "EN_COURS";
        }

        return STATUT_TERMINEE;
    }

    private int sessionStatutOrdre(String statut) {
        return switch (statut) {
            case "EN_COURS"  -> 0;
            case "A_VENIR"   -> 1;
            default          -> 2;
        };
    }

    private Lot resolverLotDepuisRotation(Rotation rotation, List<Lot> lots) {
        if (rotation.getStudentGroup() == null) return null;
        if (rotation.getStudentGroup().getLot() == null) return null;
        Long lotId = rotation.getStudentGroup().getLot().getId();
        return lots.stream()
                .filter(l -> l.getId().equals(lotId))
                .findFirst()
                .orElse(null);
    }

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

    private List<PlanningCellResponse> buildPlanning(List<Rotation> rotations) {
        LocalDateTime maintenant = LocalDateTime.now(ZoneId.of("Africa/Tunis"));

        List<PlanningCellResponse> cells = rotations.stream()
                .filter(r -> r.getDebutCreneau() != null)
                .filter(r -> r.getDebutCreneau().toLocalDate()
                        .equals(maintenant.toLocalDate()))
                .map(r -> PlanningCellResponse.builder()
                        .heure(r.getDebutCreneau().format(TIME_FMT))
                        .lotNumero(getLotNumeroPourRotation(r))
                        .statut(mapRotationStatutToPlanningStatut(r, maintenant))
                        .build())
                .collect(Collectors.toList());

        cells.sort(Comparator
                .comparing(PlanningCellResponse::getHeure)
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
                .findFirstByEvaluateurIdAndStationIdOrderByIdDesc(evaluateurId, stationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Rotation introuvable : evaluateur=" + evaluateurId
                                + LOG_ETUDIANT_STATION + stationId));

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

    /**
     * Recalcule le score final en appliquant les pondérations de l'exam-service.
     *
     * Formule identique à ScoreUtils.calculerScore (Flutter) :
     *   - BINAIRE  : valeur ∈ {0,1}  → score += valeur × pondération
     *   - NUMERIQUE: valeur ∈ [0, pondération] → score += valeur
     *
     * Fallback (exam-service indisponible) : somme des valeurs brutes.
     */
    private void recalculerScoreFinal(Notation notation) {
        List<NotationItem> items = notationItemRepository.findByNotationId(notation.getId());

        if (items.isEmpty()) {
            notation.setScore_final(0.0f);
            notationRepository.save(notation);
            return;
        }

        Map<Long, ExamServiceClient.ItemInfo> itemInfos =
                examServiceClient.getItemInfosForGrille(notation.getGrilleId());

        final boolean pondérationsDisponibles = !itemInfos.isEmpty();
        if (!pondérationsDisponibles) {
            log.warn("Pondérations indisponibles (grille={}) — score calculé en fallback",
                    notation.getGrilleId());
        }

        float score = 0f;
        for (NotationItem ni : items) {
            if (ni.getValeur() == null) continue;
            float valeur = ni.getValeur();

            if (pondérationsDisponibles) {
                ExamServiceClient.ItemInfo info = itemInfos.get(ni.getItemId());
                if (info != null && "BINAIRE".equalsIgnoreCase(info.type())) {
                    score += valeur * (float) info.ponderation();
                } else {
                    score += valeur;
                }
            } else {
                score += valeur;
            }
        }

        notation.setScore_final(score);
        notationRepository.save(notation);
        log.debug("Score recalculé — notation={} grille={} : {} pts{}",
                notation.getId(), notation.getGrilleId(), score,
                pondérationsDisponibles ? "" : " (fallback)");
    }

    // =========================================================================
    // MÉTHODES PRIVÉES — Mapping et utilitaires
    // =========================================================================

    private String mapRotationStatutToPlanningStatut(Rotation rotation, LocalDateTime maintenant) {
        if (rotation.getStatut() == RotationStatus.TERMINE) {
            return "TERMINE";
        }
        LocalDateTime debut     = rotation.getDebutCreneau();
        LocalDateTime finReelle = debut.plusMinutes((long) DUREE_STATION_MIN + GRACE_PERIOD_MIN);

        if (maintenant.isBefore(debut)) {
            return "A_VENIR";
        }
        if (!maintenant.isAfter(finReelle)) {
            return "A_VENIR";
        }
        return "TERMINE";
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

    private boolean isNotationVerrouillée(Long participationId) {
        return rotationAssignmentRepository.findByParticipationId(participationId)
                .flatMap(a -> notationRepository.findByAssignmentId(a.getId()))
                .map(n -> Boolean.TRUE.equals(n.getVerouillee()))
                .orElse(false);
    }
}