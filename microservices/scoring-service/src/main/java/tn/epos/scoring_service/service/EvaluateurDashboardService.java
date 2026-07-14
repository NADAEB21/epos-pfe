package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.dto.dashboard.*;
import tn.epos.scoring_service.dto.websocket.LotStatusMessage;
import tn.epos.scoring_service.dto.websocket.ScoreUpdateMessage;
import tn.epos.scoring_service.entities.*;
import tn.epos.scoring_service.repositories.*;
import tn.epos.scoring_service.client.ExamServiceClient;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service d'agrégation pour le dashboard de l'évaluateur (app mobile Flutter).
 *
 * Fusion ADR-0012 (Temps effectif) et BF6.1 (WebSockets).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class EvaluateurDashboardService {

    // ── Repositories ─────────────────────────────────────────────────────────

    private final ILotRepository                 lotRepository;
    private final IRotationRepository            rotationRepository;
    private final IRotationAssignmentRepository  rotationAssignmentRepository;
    private final INotationRepository            notationRepository;
    private final INotationItemRepository        notationItemRepository;
    private final IExamenParticipationRepository participationRepository;
    private final ExamServiceClient              examServiceClient;

    /** Horloge injectable ADR-0010. */
    private final Clock clock;

    /** BF6.1 — Template STOMP pour le push WebSocket. */
    private final SimpMessagingTemplate          messagingTemplate;

    // ── Constantes ────────────────────────────────────────────────────────────

    private static final DateTimeFormatter TIME_FMT            = DateTimeFormatter.ofPattern("HH:mm");
    private static final String            STATUT_TERMINEE     = "TERMINEE";
    private static final String            LOG_ETUDIANT_STATION = " station=";

    private static final int DUREE_STATION_MIN = 15;
    private static final int GRACE_PERIOD_MIN  = 30;

    private static final String TOPIC_SCORES = "/topic/stations/%d/scores";
    private static final String TOPIC_LOT    = "/topic/lots/%d/status";

    // =========================================================================
    // 1. DASHBOARD COMPLET
    // =========================================================================

    @Transactional(readOnly = true)
    public EvaluateurDashboardResponse buildDashboard(Long evaluateurId) {
        log.debug("Building dashboard for evaluateur {}", evaluateurId);

        List<Long> examenIds = rotationRepository.findDistinctExamenIdsByEvaluateurId(evaluateurId);

        // #189 — un examen CONFIGURE (jamais lancé) ou TERMINE (déjà clôturé) n'a
        // pas de session "à faire" pour l'évaluateur aujourd'hui. On lit le statut
        // de chaque examen concerné UNE fois, puis on ne charge les rotations que
        // pour les examens réellement EN_COURS.
        Map<Long, ExamServiceClient.ExamTiming> timingByExamen = examenIds.stream()
                .collect(Collectors.toMap(id -> id, examServiceClient::getExamTiming));

        // On EXCLUT seulement quand le statut est CONNU et n'est pas EN_COURS.
        //
        // FAIL OPEN, volontairement : si le statut est inconnu (null — exam-service injoignable,
        // 403, réponse malformée…), on GARDE l'examen. Un statut inconnu ne doit JAMAIS faire
        // disparaître les sessions d'un évaluateur.
        //
        // Vécu : /api/examens/{id} était interdit à l'évaluateur (403) ; le repli renvoyait
        // statut = null ; le filtre strict "equals EN_COURS" éliminait alors TOUS les examens et
        // le dashboard évaluateur devenait VIDE le jour J — une panne bien pire que le bug qu'il
        // corrigeait. Montrer un examen de trop est gênant ; n'en montrer aucun est fatal.
        List<Long> liveExamenIds = timingByExamen.entrySet().stream()
                .filter(e -> {
                    String statut = e.getValue().statut();
                    if (statut == null) {
                        log.warn("Statut inconnu pour l'examen {} (exam-service injoignable ou refusé) "
                                + "— examen CONSERVÉ dans le dashboard de l'évaluateur {} plutôt que masqué.",
                                e.getKey(), evaluateurId);
                        return true;
                    }
                    return "EN_COURS".equals(statut);
                })
                .map(Map.Entry::getKey)
                .toList();

        List<Rotation> rotations = liveExamenIds.isEmpty()
                ? List.of()
                : rotationRepository.findByEvaluateurIdAndStudentGroup_Lot_ExamenIdIn(evaluateurId, liveExamenIds);

        List<Lot> lots = resolverLotsDepuisRotations(rotations);
        LocalDateTime rawNow = LocalDateTime.now(clock);

        List<SessionResponse>      sessions = buildSessions(rotations, lots, rawNow, timingByExamen);
        StatsResponse              stats    = buildStats(sessions, lots);
        List<PlanningCellResponse> planning = buildPlanning(rotations, rawNow, timingByExamen);

        return EvaluateurDashboardResponse.builder()
                .serverNow(rawNow)
                .sessions(sessions)
                .stats(stats)
                .planning(planning)
                .build();
    }

    private List<Lot> resolverLotsDepuisRotations(List<Rotation> rotations) {
        Map<Long, Lot> lotsParId = new java.util.LinkedHashMap<>();
        for (Rotation r : rotations) {
            if (r.getStudentGroup() != null && r.getStudentGroup().getLot() != null) {
                Lot lot = r.getStudentGroup().getLot();
                lotsParId.putIfAbsent(lot.getId(), lot);
            }
        }
        return new ArrayList<>(lotsParId.values());
    }

    // =========================================================================
    // 2. DÉTAIL D'UN LOT
    // =========================================================================

    @Transactional(readOnly = true)
    public LotDetailResponse getLotDetail(Long stationId, Integer lotNumero, Long evaluateurId) {
        List<Rotation> rotations = rotationRepository.findByEvaluateurId(evaluateurId);

        // AVANT : filtrait seulement par lotNumero → ambigu si l'évaluateur a des
        // rotations sur plusieurs examens (numeroLot n'est unique que par examen).
        // MAINTENANT : on résout la Rotation exacte via stationId (paramètre reçu
        // mais jamais utilisé avant) + lotNumero → plus d'ambiguïté inter-examens.
        Rotation rotation = rotations.stream()
                .filter(r -> stationId.equals(r.getStationId())
                        && r.getStudentGroup() != null
                        && r.getStudentGroup().getLot() != null
                        && lotNumero.equals(r.getStudentGroup().getLot().getNumeroLot()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Rotation introuvable pour évaluateur=" + evaluateurId
                                + ", station=" + stationId + ", lot=" + lotNumero));

        Lot lot = rotation.getStudentGroup().getLot();
        int totalLots = lotRepository.countByExamenId(lot.getExamenId());

        // AVANT : participationRepository.findByLotId(lot.getId()) → retournait
        // TOUS les étudiants du lot (les 4 groupes confondus, 16-20 étudiants).
        // MAINTENANT : on ne prend que les participations liées à CETTE rotation
        // précise (= le StudentGroup de cette station à ce créneau).
        List<RotationAssignment> assignments =
                rotationAssignmentRepository.findByRotationId(rotation.getId());

        List<LotDetailResponse.EtudiantLotResponse> etudiants = assignments.stream()
                .map(RotationAssignment::getParticipation)
                .filter(p -> p != null && p.getEtudiant() != null)
                .map(p -> LotDetailResponse.EtudiantLotResponse.builder()
                        .id(p.getEtudiant().getId())
                        .nom(p.getEtudiant().getNom())
                        .prenom(p.getEtudiant().getPrenom())
                        .absent(!Boolean.TRUE.equals(p.getEst_present()))
                        .verrouille(isNotationVerrouillée(p.getId()))
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

    private List<LotDetailResponse.NotationItemResponse> loadNotationItems(Long participationId) {
        return rotationAssignmentRepository.findByParticipationId(participationId)
                .flatMap(a -> notationRepository.findByAssignmentId(a.getId()))
                .map(n -> notationItemRepository.findByNotationId(n.getId()).stream()
                        .map(ni -> LotDetailResponse.NotationItemResponse.builder()
                                .itemId(ni.getItemId()).valeur(ni.getValeur()).build())
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());
    }

    // =========================================================================
    // 3. ACTIONS & BROADCAST
    // =========================================================================

    public void saisirNotation(SaisirNotationRequest request, Long evaluateurId) {
        Set<Long> feuillesValides = examServiceClient.getItemIdsForGrille(request.getGrilleId());
        if (!feuillesValides.isEmpty() && !feuillesValides.contains(request.getItemId())) {
            throw new BusinessException(
                    "L'item " + request.getItemId() + " est un critère parent (il a des sous-critères) "
                            + "— seuls ses sous-critères sont notables.");
        }

        ExamenParticipation participation = resolverParticipation(request.getEtudiantId(), request.getStationId());
        RotationAssignment assignment = rotationAssignmentRepository.findByParticipationId(participation.getId())
                .orElseGet(() -> createAssignment(participation, request.getStationId(), evaluateurId));

        Notation notation = notationRepository.findByAssignmentId(assignment.getId())
                .orElseGet(() -> createNotation(assignment, request.getStationId(), request.getGrilleId()));

        if (Boolean.TRUE.equals(notation.getVerouillee())) throw new BusinessException("Verrouillé");

        Optional<NotationItem> existingItem = notationItemRepository.findByNotationIdAndItemId(notation.getId(), request.getItemId());
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
        broadcastScore(notation, request.getStationId());
    }

    public void validerEtudiant(Long etudiantId, Long stationId, Long evaluateurId, ValiderEtudiantRequest request) {
        ExamenParticipation participation = resolverParticipation(etudiantId, stationId);
        RotationAssignment assignment = rotationAssignmentRepository.findByParticipationId(participation.getId())
                .orElseGet(() -> createAssignment(participation, stationId, evaluateurId));
        Notation notation = notationRepository.findByAssignmentId(assignment.getId())
                .orElseGet(() -> createNotation(assignment, stationId, request.getGrilleId()));

        if (request.isAbsent()) {
            notationItemRepository.deleteAll(notationItemRepository.findByNotationId(notation.getId()));
            notation.setScore_final(0.0f);
        }
        notation.setVerouillee(true);
        notationRepository.save(notation);
        participation.setEst_present(!request.isAbsent());
        participation.setNote(notation.getScore_final());
        participation.setCommentaire(request.getCommentaire());
        participationRepository.save(participation);
        broadcastScore(notation, stationId);
    }

    public void validerLot(Long lotId, Long evaluateurId) {
        Lot lot = lotRepository.findById(lotId).orElseThrow(() -> new ResourceNotFoundException("Lot introuvable"));
        lot.setStatut(LotStatus.TERMINE);
        lotRepository.save(lot);

        if (lot.getGroups() != null) {
            lot.getGroups().forEach(g -> g.getRotations().forEach(r -> {
                r.setStatut(RotationStatus.TERMINE);
                rotationRepository.save(r);
            }));
        }
        broadcastLotStatus(lotId, "TERMINE");
    }

    // =========================================================================
    // MÉTHODES PRIVÉES
    // =========================================================================

    private List<SessionResponse> buildSessions(List<Rotation> rotations, List<Lot> lots,
                                                LocalDateTime rawNow,
                                                Map<Long, ExamServiceClient.ExamTiming> timingByExam) {
        Map<Long, Lot> lotsParId = lots.stream().collect(Collectors.toMap(Lot::getId, l -> l));
        return rotations.stream()
                .filter(r -> r.getDebutCreneau() != null && r.getStationId() != null)
                .map(rotation -> {
                    ExamServiceClient.ExamTiming timing = timingFor(rotation, timingByExam);
                    LocalDateTime maintenant = effectiveNow(timing, rawNow);
                    int dureeMin = dureeMinFor(timing);
                    String statut = resolveSessionStatut(rotation, maintenant, dureeMin);
                    Lot lotLie = resolverLotDepuisRotation(rotation, lotsParId);

                    return SessionResponse.builder()
                            .id(lotLie != null ? lotLie.getId() : rotation.getId())
                            .stationId(rotation.getStationId())
                            .stationNom(examServiceClient.getStationInfo(rotation.getStationId()).nom())
                            .statut(statut)
                            .heureDebut(rotation.getDebutCreneau().format(TIME_FMT))
                            .heureFin(rotation.getDebutCreneau().plusMinutes(dureeMin).format(TIME_FMT))
                            .lotActuel(lotLie != null ? lotLie.getNumeroLot() : 0)
                            .debutPrevu(debutPrevu(rotation, timing, rawNow))
                            .enPause(timing.enPause()).build();
                })
                .sorted(Comparator.comparingInt((SessionResponse s) -> sessionStatutOrdre(s.getStatut()))
                        .thenComparing(SessionResponse::getHeureDebut))
                .collect(Collectors.toList());
    }

    private String resolveSessionStatut(Rotation rotation, LocalDateTime maintenant, int dureeMin) {
        if (rotation.getStatut() == RotationStatus.TERMINE) return STATUT_TERMINEE;
        LocalDateTime debut = rotation.getDebutCreneau();
        LocalDateTime finReelle = debut.plusMinutes((long) dureeMin + GRACE_PERIOD_MIN);
        if (maintenant.isBefore(debut)) return "A_VENIR";
        if (!maintenant.isAfter(finReelle)) return "EN_COURS";
        return STATUT_TERMINEE;
    }

    private int sessionStatutOrdre(String statut) {
        return switch (statut) {
            case "EN_COURS" -> 0;
            case "A_VENIR"  -> 1;
            default         -> 2;
        };
    }

    private LocalDateTime effectiveNow(ExamServiceClient.ExamTiming timing, LocalDateTime rawNow) {
        return rawNow.minusSeconds(pauseSeconds(timing, rawNow));
    }

    private long pauseSeconds(ExamServiceClient.ExamTiming timing, LocalDateTime rawNow) {
        long pausedSec = Math.max(0, timing.totalPauseSec());
        if (timing.enPause() && timing.pausedAt() != null) {
            long live = Duration.between(timing.pausedAt().atZone(clock.getZone()), rawNow.atZone(clock.getZone())).getSeconds();
            if (live > 0) pausedSec += live;
        }
        return pausedSec;
    }

    private LocalDateTime debutPrevu(Rotation rotation, ExamServiceClient.ExamTiming timing, LocalDateTime rawNow) {
        return rotation.getDebutCreneau().plusSeconds(pauseSeconds(timing, rawNow));
    }

    private int dureeMinFor(ExamServiceClient.ExamTiming timing) {
        return (timing.dureeStationMin() != null && timing.dureeStationMin() > 0) ? timing.dureeStationMin() : DUREE_STATION_MIN;
    }

    private void broadcastScore(Notation notation, Long stationId) {
        if (notation.getAssignment() == null || notation.getAssignment().getParticipation().getEtudiant() == null) return;
        Long etudiantId = notation.getAssignment().getParticipation().getEtudiant().getId();
        ScoreUpdateMessage msg = ScoreUpdateMessage.builder()
                .etudiantId(etudiantId).stationId(stationId).score(notation.getScore_final())
                .verrouille(Boolean.TRUE.equals(notation.getVerouillee())).build();
        messagingTemplate.convertAndSend(String.format(TOPIC_SCORES, stationId), msg);
    }

    private void broadcastLotStatus(Long lotId, String statut) {
        messagingTemplate.convertAndSend(String.format(TOPIC_LOT, lotId),
                LotStatusMessage.builder().lotId(lotId).statut(statut).build());
    }

    private ExamenParticipation resolverParticipation(Long etudiantId, Long stationId) {
        return participationRepository.findByEtudiantIdAndStationId(etudiantId, stationId)
                .orElseGet(() -> participationRepository.findByEtudiantId(etudiantId).stream().findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException("Inexistant")));
    }

    private List<PlanningCellResponse> buildPlanning(List<Rotation> rotations, LocalDateTime rawNow, Map<Long, ExamServiceClient.ExamTiming> timingByExam) {
        return rotations.stream()
                .filter(r -> r.getDebutCreneau() != null && r.getDebutCreneau().toLocalDate().equals(rawNow.toLocalDate()))
                .map(r -> {
                    ExamServiceClient.ExamTiming t = timingFor(r, timingByExam);
                    return PlanningCellResponse.builder()
                            .heure(r.getDebutCreneau().format(TIME_FMT))
                            .lotNumero(r.getStudentGroup() != null ? r.getStudentGroup().getLot().getNumeroLot() : 0)
                            .statut(mapRotationStatutToPlanningStatut(r, effectiveNow(t, rawNow), dureeMinFor(t))).build();
                }).collect(Collectors.toList());
    }

    private String mapRotationStatutToPlanningStatut(Rotation r, LocalDateTime m, int d) {
        if (r.getStatut() == RotationStatus.TERMINE) return "TERMINE";
        LocalDateTime fin = r.getDebutCreneau().plusMinutes((long) d + GRACE_PERIOD_MIN);
        return m.isBefore(r.getDebutCreneau()) || !m.isAfter(fin) ? "A_VENIR" : "TERMINE";
    }

    private void recalculerScoreFinal(Notation notation) {
        List<NotationItem> items = notationItemRepository.findByNotationId(notation.getId());
        Map<Long, ExamServiceClient.ItemInfo> infos = examServiceClient.getItemInfosForGrille(notation.getGrilleId());
        float score = 0f;
        for (NotationItem ni : items) {
            ExamServiceClient.ItemInfo info = infos.get(ni.getItemId());
            score += (info != null && "BINAIRE".equals(info.type())) ? ni.getValeur() * (float) info.ponderation() : ni.getValeur();
        }
        notation.setScore_final(score);
        notationRepository.save(notation);
    }

    private Map<Long, ExamServiceClient.ExamTiming> fetchTimings(List<Rotation> rotations) {
        Map<Long, ExamServiceClient.ExamTiming> map = new HashMap<>();
        rotations.forEach(r -> {
            Long exId = (r.getStudentGroup() != null && r.getStudentGroup().getLot() != null) ? r.getStudentGroup().getLot().getExamenId() : null;
            if (exId != null) map.computeIfAbsent(exId, examServiceClient::getExamTiming);
        });
        return map;
    }

    private ExamServiceClient.ExamTiming timingFor(Rotation r, Map<Long, ExamServiceClient.ExamTiming> m) {
        Long exId = (r.getStudentGroup() != null && r.getStudentGroup().getLot() != null) ? r.getStudentGroup().getLot().getExamenId() : null;
        return (exId != null && m.get(exId) != null) ? m.get(exId) : ExamServiceClient.ExamTiming.neutral();
    }

    private Lot resolverLotDepuisRotation(Rotation rotation, Map<Long, Lot> lotsParId) {
        return (rotation.getStudentGroup() != null && rotation.getStudentGroup().getLot() != null)
                ? lotsParId.get(rotation.getStudentGroup().getLot().getId()) : null;
    }

    private StatsResponse buildStats(List<SessionResponse> sessions, List<Lot> lots) {
        return StatsResponse.builder().sessionsAssignees(sessions.size())
                .totalEtudiants(lots.stream().mapToInt(l -> l.getTailleLot() != null ? l.getTailleLot() : 0).sum())
                .lotsValides((int) lots.stream().filter(l -> l.getStatut() == LotStatus.TERMINE).count())
                .totalLots(lots.size()).build();
    }

    private RotationAssignment createAssignment(ExamenParticipation p, Long sId, Long eId) {
        Rotation r = rotationRepository.findFirstByEvaluateurIdAndStationIdOrderByIdDesc(eId, sId).orElseThrow(() -> new ResourceNotFoundException("Rotation out"));
        RotationAssignment a = new RotationAssignment();
        a.setRotation(r); a.setParticipation(p); a.setPresenceConfirmee(true);
        return rotationAssignmentRepository.save(a);
    }

    private Notation createNotation(RotationAssignment a, Long sId, Long gId) {
        Notation n = new Notation();
        n.setAssignment(a); n.setStationId(sId); n.setGrilleId(gId); n.setScore_final(0.0f); n.setVerouillee(false);
        return notationRepository.save(n);
    }

    private boolean isNotationVerrouillée(Long pId) {
        return rotationAssignmentRepository.findByParticipationId(pId)
                .flatMap(a -> notationRepository.findByAssignmentId(a.getId()))
                .map(n -> Boolean.TRUE.equals(n.getVerouillee())).orElse(false);
    }
}