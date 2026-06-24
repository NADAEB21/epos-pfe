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
 * Corrections apportées dans cette version :
 *
 *   FIX 4 — Dashboard vide (sessions=[], stats=0, planning=[]).
 *            Cause racine : Lot.evaluateurId n'est jamais renseigné lors de la
 *            répartition dans LotAssignmentService.repartir(). Par conséquent,
 *            lotRepository.findByEvaluateurId(evaluateurId) retournait toujours
 *            une liste vide, ce qui rendait resolverLotDepuisRotation() incapable
 *            de retrouver les lots liés aux rotations → sessions sans étudiants
 *            ni lot, stats à zéro, planning vide.
 *            Correction : les lots sont désormais déduits depuis les rotations de
 *            l'évaluateur via la chaîne Rotation → StudentGroup → Lot, qui est la
 *            seule source fiable du lien évaluateur ↔ lot dans ce modèle de données.
 *
 *   FIX 5 — getLotDetail() échouait pour la même raison (findByEvaluateurIdAndNumeroLot
 *            cherchait sur Lot.evaluateurId toujours null). Correction : le lot est
 *            retrouvé depuis les rotations de l'évaluateur.
 *
 *   FIX 6 — Broadcast WebSocket (BF6.1) : après chaque saisirNotation() et validerLot(),
 *            le score mis à jour est publié sur /topic/stations/{id}/scores et
 *            le statut du lot sur /topic/lots/{id}/status.
 *
 *   FIX 1-3 — Conservés sans modification (log détaillé, Rotation TERMINE, grâce 30min).
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


    /**
     * Horloge injectable pinnée sur {@code app.timezone} (ADR-0010, voir
     * {@code ClockConfig}). Remplace {@code now(ZoneId.of("Africa/Tunis"))}
     * codé en dur : "maintenant" et {@code paused_at} doivent vivre dans la même
     * zone pour que le calcul du temps effectif (ADR-0012 §0) soit correct.
     */
    private final Clock clock;

    /** BF6.1 — Template STOMP pour le push WebSocket temps réel. */
    private final SimpMessagingTemplate          messagingTemplate;



    // ── Constantes ────────────────────────────────────────────────────────────

    private static final DateTimeFormatter TIME_FMT            = DateTimeFormatter.ofPattern("HH:mm");
    private static final String            STATUT_TERMINEE     = "TERMINEE";
    private static final String            LOG_ETUDIANT_STATION = " station=";

    /**
     * Durée nominale d'une station en minutes — <b>repli uniquement</b>.
     * ADR-0012 §0 : la durée réelle est désormais lue depuis
     * {@code Examen.dureeStationMin} (via {@link ExamServiceClient.ExamTiming}) ;
     * cette constante ne sert que si l'examen n'est pas résolvable.
     */

    private static final int DUREE_STATION_MIN = 15;
    private static final int GRACE_PERIOD_MIN  = 30;

    // ── Topics WebSocket ──────────────────────────────────────────────────────

    /** Destination du topic score pour une station donnée. */
    /** Topics WebSocket — BF6.1. */
    private static final String TOPIC_SCORES = "/topic/stations/%d/scores";
    private static final String TOPIC_LOT    = "/topic/lots/%d/status";


    // =========================================================================
    // 1. DASHBOARD COMPLET
    // =========================================================================

    /**
     * FIX 4 — Les lots sont déduits depuis les rotations de l'évaluateur
     * (Rotation → StudentGroup → Lot) au lieu de lotRepository.findByEvaluateurId()
     * qui retourne toujours vide car Lot.evaluateurId n'est jamais renseigné.
     */
    @Transactional(readOnly = true)
    public EvaluateurDashboardResponse buildDashboard(Long evaluateurId) {
        log.debug("Building dashboard for evaluateur {}", evaluateurId);

        List<Rotation> rotations = rotationRepository.findByEvaluateurId(evaluateurId);

        // FIX 4 — déduire les lots depuis les rotations, seule source fiable.
        List<Lot> lots = deriverLotsDepuisRotations(rotations);

        // ADR-0012 §0 : "maintenant" effectif. On lit l'état de pause de chaque
        // examen concerné UNE fois, puis on dérive un temps effectif par examen
        // (horloge murale moins le temps de pause) au lieu de comparer l'heure
        // brute à debutCreneau — qui dérivait dès la première pause.
        LocalDateTime rawNow = LocalDateTime.now(clock);
        Map<Long, ExamServiceClient.ExamTiming> timingByExam = fetchTimings(rotations);

        List<SessionResponse>      sessions = buildSessions(rotations, lots, rawNow, timingByExam);
        StatsResponse              stats    = buildStats(sessions, lots);
        List<PlanningCellResponse> planning = buildPlanning(rotations, rawNow, timingByExam);

        log.debug("Dashboard évaluateur {} : {} rotation(s), {} lot(s), {} session(s)",
                evaluateurId, rotations.size(), lots.size(), sessions.size());

        return EvaluateurDashboardResponse.builder()
                // ADR-0012 : horloge serveur unique renvoyée à l'app évaluateur
                // pour corriger l'offset de son horloge locale (compte à rebours).
                .serverNow(rawNow)
                .sessions(sessions)
                .stats(stats)
                .planning(planning)
                .build();
    }

    /**
     * Extrait les lots uniques liés aux rotations de l'évaluateur.
     *
     * <p>La chaîne utilisée est : Rotation → StudentGroup → Lot.
     * On utilise un LinkedHashMap pour dédupliquer par lotId tout en
     * préservant l'ordre d'apparition (stabilité de l'affichage Flutter).</p>
     */
    private List<Lot> deriverLotsDepuisRotations(List<Rotation> rotations) {
        return rotations.stream()
                .filter(r -> r.getStudentGroup() != null
                        && r.getStudentGroup().getLot() != null)
                .map(r -> r.getStudentGroup().getLot())
                .collect(Collectors.toMap(
                        Lot::getId,
                        l -> l,
                        (existant, doublon) -> existant,   // keep first occurrence
                        LinkedHashMap::new))
                .values()
                .stream()
                .collect(Collectors.toList());
    }

    // =========================================================================
    // 2. DÉTAIL D'UN LOT AVEC ÉTUDIANTS
    // =========================================================================

    /**
     * FIX 5 — Retrouve le lot via les rotations de l'évaluateur au lieu de
     * findByEvaluateurIdAndNumeroLot() qui échouait (Lot.evaluateurId = null).
     */
    @Transactional(readOnly = true)
    public LotDetailResponse getLotDetail(Long stationId, Integer lotNumero, Long evaluateurId) {
        log.debug("getLotDetail stationId={} lotNumero={} evaluateur={}",
                stationId, lotNumero, evaluateurId);

        List<Rotation> rotations = rotationRepository.findByEvaluateurId(evaluateurId);

        // FIX 5 — chercher le lot depuis les rotations, pas depuis Lot.evaluateurId.
        Lot lot = rotations.stream()
                .filter(r -> r.getStudentGroup() != null
                        && r.getStudentGroup().getLot() != null
                        && lotNumero.equals(r.getStudentGroup().getLot().getNumeroLot()))
                .map(r -> r.getStudentGroup().getLot())
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Lot numéro " + lotNumero + " introuvable pour l'évaluateur " + evaluateurId
                                + ". Vérifiez que les rotations sont générées pour ce lot."));

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
    // 3. SAISIR UNE NOTATION — avec broadcast WebSocket (FIX 6 / BF6.1)
    // =========================================================================

    public void saisirNotation(SaisirNotationRequest request, Long evaluateurId) {
        log.debug("saisirNotation etudiant={} station={} grille={} item={} valeur={}",
                request.getEtudiantId(), request.getStationId(),
                request.getGrilleId(), request.getItemId(), request.getValeur());

        // FIX 1 — log explicite si la chaîne ExamenParticipation→Lot→StudentGroup→Rotation est brisée.
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

        // Upsert du NotationItem : mise à jour si existe, création sinon.
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

        // FIX 6 — Broadcast WebSocket du score recalculé (BF6.1).
        broadcastScore(notation, request.getStationId());

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
            List<NotationItem> items = notationItemRepository.findByNotationId(notation.getId());
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

        // FIX 6 — Broadcast WebSocket : score verrouillé visible par les autres évaluateurs (BF6.1).
        broadcastScore(notation, stationId);

        log.info("Étudiant {} {} à la station {} — note={} (évaluateur {})",
                etudiantId,
                request.isAbsent() ? "ABSENT" : "validé",
                stationId, notation.getScore_final(), evaluateurId);
    }

    // =========================================================================
    // 5. VALIDER UN LOT — avec broadcast WebSocket (FIX 2 + FIX 6)
    // =========================================================================

    /**
     * FIX 2 — Marque Lot.statut et Rotation.statut = TERMINE.
     * FIX 6 — Broadcast /topic/lots/{id}/status après validation.
     */
    public void validerLot(Long lotId, Long evaluateurId) {
        log.debug("validerLot lot={} evaluateur={}", lotId, evaluateurId);

        Lot lot = lotRepository.findById(lotId)
                .orElseThrow(() -> new ResourceNotFoundException("Lot introuvable : " + lotId));

        // Vérification ownership via les rotations (Lot.evaluateurId n'est pas fiable — FIX 4).
        boolean isOwner = rotationRepository.findByEvaluateurId(evaluateurId).stream()
                .anyMatch(r -> r.getStudentGroup() != null
                        && r.getStudentGroup().getLot() != null
                        && lot.getId().equals(r.getStudentGroup().getLot().getId()));

        if (!isOwner) {
            throw new BusinessException(
                    "Accès refusé : le lot " + lotId
                            + " n'est pas associé à l'évaluateur " + evaluateurId);
        }

        lot.setStatut(LotStatus.TERMINE);
        lotRepository.save(lot);

        // FIX 2 — Marque aussi toutes les rotations du lot comme TERMINE.
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

        // FIX 6 — Broadcast WebSocket : statut du lot mis à jour (BF6.1).
        broadcastLotStatus(lotId, "TERMINE");

        log.info("Lot {} validé et rotations associées marquées TERMINE (évaluateur {})",
                lotId, evaluateurId);
    }

    // =========================================================================
    // MÉTHODES PRIVÉES — Construction des réponses
    // =========================================================================
    private List<SessionResponse> buildSessions(List<Rotation> rotations, List<Lot> lots,
                                                LocalDateTime rawNow,
                                                Map<Long, ExamServiceClient.ExamTiming> timingByExam) {

            // Index lots par ID pour une résolution O(1) (FIX 4 de ta collègue)
            Map<Long, Lot> lotsParId = lots.stream()
                    .collect(Collectors.toMap(Lot::getId, l -> l));

            return rotations.stream()
                    .filter(r -> r.getDebutCreneau() != null && r.getStationId() != null)
                    .map(rotation -> {
                        ExamServiceClient.ExamTiming timing = timingFor(rotation, timingByExam);
                        LocalDateTime maintenant = effectiveNow(timing, rawNow);
                        int dureeMin = dureeMinFor(timing);

                        // ADR-0012 : temps absolu prédit
                        LocalDateTime debutPrevu = debutPrevu(rotation, timing, rawNow);

                        String statut     = resolveSessionStatut(rotation, maintenant, dureeMin);
                        String heureDebut = rotation.getDebutCreneau().format(TIME_FMT);
                        String heureFin   = rotation.getDebutCreneau()
                                .plusMinutes(dureeMin)
                                .format(TIME_FMT);

                        Lot lotLie      = resolverLotDepuisRotation(rotation, lotsParId);
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
                                .debutPrevu(debutPrevu)
                                .avertissementLeadSec(timing.avertissementLeadSec())
                                .enPause(timing.enPause())
                                .build();
                    })
                    .sorted(Comparator
                            .comparingInt((SessionResponse s) -> sessionStatutOrdre(s.getStatut()))
                            .thenComparing(SessionResponse::getHeureDebut))
                    .collect(Collectors.toList());
        }

    /**
     * FIX 3 — Statut de session avec période de grâce de 30 min.
     *
<<<<<<< HEAD
<<<<<<< HEAD
=======
>>>>>>> 4bc9e51 (fix dashboard evaluateur)
     * Priorité (ordre strict) :
     *   1. Rotation.statut == TERMINE → TERMINEE  (validation manuelle via validerLot)
     *   2. Lot lié TERMINE            → TERMINEE  (validation manuelle via validerLot)
     *   3. debutCreneau > maintenant  → A_VENIR
<<<<<<< HEAD
     *   4. maintenant ≤ debut + duree + grace  → EN_COURS
     *   5. maintenant > debut + duree + grace  → TERMINEE (expiration automatique)
     *
     * <p>{@code maintenant} est le <b>temps effectif</b> (ADR-0012 §0) et
     * {@code dureeMin} provient de la config examen, non plus d'une constante.
=======
     * <p>Priorité (ordre strict) :
     * <ol>
     *   <li>Rotation.statut == TERMINE → TERMINEE (validation manuelle)</li>
     *   <li>Lot lié TERMINE → TERMINEE (validation manuelle)</li>
     *   <li>debutCreneau > maintenant → A_VENIR</li>
     *   <li>maintenant ≤ debut + duree + grace → EN_COURS</li>
     *   <li>maintenant > debut + duree + grace → TERMINEE (expiration)</li>
     * </ol>
>>>>>>> 6112141 (add websocket config)
=======
     *   4. maintenant ≤ debut + duree + grace → EN_COURS
     *   5. sinon                      → TERMINEE  (expiration automatique)
>>>>>>> 4bc9e51 (fix dashboard evaluateur)
     */
    private String resolveSessionStatut(Rotation rotation, LocalDateTime maintenant, int dureeMin) {
        if (rotation.getStatut() == RotationStatus.TERMINE) {
            return STATUT_TERMINEE;
        }

        if (rotation.getStudentGroup() != null
                && rotation.getStudentGroup().getLot() != null
                && rotation.getStudentGroup().getLot().getStatut() == LotStatus.TERMINE) {
            return STATUT_TERMINEE;
        }

        LocalDateTime debut     = rotation.getDebutCreneau();
        LocalDateTime finReelle = debut.plusMinutes((long) dureeMin + GRACE_PERIOD_MIN);

        if (maintenant.isBefore(debut))         return "A_VENIR";
        if (!maintenant.isAfter(finReelle))     return "EN_COURS";
        return STATUT_TERMINEE;
    }

    private int sessionStatutOrdre(String statut) {
        return switch (statut) {
            case "EN_COURS" -> 0;
            case "A_VENIR"  -> 1;
            default         -> 2;
        };
    }

    /** Résout le lot lié à une rotation via l'index O(1) construit dans buildSessions. */
    private Lot resolverLotDepuisRotation(Rotation rotation, Map<Long, Lot> lotsParId) {
        if (rotation.getStudentGroup() == null
                || rotation.getStudentGroup().getLot() == null) return null;
        return lotsParId.get(rotation.getStudentGroup().getLot().getId());
    }

    // =========================================================================
    // MÉTHODES PRIVÉES — Temps effectif réconcilié avec la pause, ADR-0012
    // =========================================================================

    /**
     * Lit l'état temporel (pause + durée) de chaque examen concerné par les
     * rotations, une seule fois par examen distinct. Une rotation sans
     * studentGroup/lot résolvable (ex. legacy) n'a pas d'examen → absente de la
     * map → repli sur l'heure murale brute via {@link #timingFor}.
     */
    private Map<Long, ExamServiceClient.ExamTiming> fetchTimings(List<Rotation> rotations) {
        Map<Long, ExamServiceClient.ExamTiming> map = new HashMap<>();
        for (Rotation r : rotations) {
            Long examenId = resolveExamenId(r);
            if (examenId != null) {
                map.computeIfAbsent(examenId, examServiceClient::getExamTiming);
            }
        }
        return map;
    }

    private Long resolveExamenId(Rotation rotation) {
        if (rotation.getStudentGroup() == null) return null;
        if (rotation.getStudentGroup().getLot() == null) return null;
        return rotation.getStudentGroup().getLot().getExamenId();
    }

    /** Timing de l'examen de la rotation, ou état neutre (pas de pause) en repli. */
    private ExamServiceClient.ExamTiming timingFor(
            Rotation rotation, Map<Long, ExamServiceClient.ExamTiming> timingByExam) {
        Long examenId = resolveExamenId(rotation);
        ExamServiceClient.ExamTiming t = (examenId != null) ? timingByExam.get(examenId) : null;
        return (t != null) ? t : ExamServiceClient.ExamTiming.neutral();
    }

    /**
     * Temps effectif = heure murale moins le temps de pause cumulé moins (si
     * l'examen est en pause maintenant) le temps écoulé depuis le début de la
     * pause en cours (ADR-0009/0010). Comparer ce temps à {@code debutCreneau}
     * (lui-même ancré sur {@code launched_at}, ADR-0010) reste correct sans
     * connaître {@code launched_at} : le décalage de pause s'annule des deux
     * côtés. Une pause "recule" donc l'horloge — une session affichée pendant
     * une pause ne dérive plus vers le mauvais étudiant.
     */
    private LocalDateTime effectiveNow(ExamServiceClient.ExamTiming timing,
                                       LocalDateTime rawNow) {
        return rawNow.minusSeconds(pauseSeconds(timing, rawNow));
    }

    /**
     * Temps de pause total (secondes) à retrancher de l'horloge murale : pauses
     * terminées cumulées + (si en pause maintenant) le temps écoulé depuis le
     * début de la pause en cours. Source unique partagée par {@link #effectiveNow}
     * (qui le soustrait à {@code now}) et par {@code debutPrevu} (qui l'ajoute à
     * {@code debutCreneau}) — les deux sens d'une même réconciliation ADR-0012,
     * de sorte que l'horloge live et le compte à rebours ne peuvent pas diverger.
     */
    private long pauseSeconds(ExamServiceClient.ExamTiming timing, LocalDateTime rawNow) {
        long pausedSec = Math.max(0, timing.totalPauseSec());
        if (timing.enPause() && timing.pausedAt() != null) {
            // Zone-aware avant de mesurer l'écart : les deux instants vivent dans
            // la zone pinnée de l'horloge (app.timezone, ADR-0010).
            ZoneId zone = clock.getZone();
            long live = Duration.between(
                    timing.pausedAt().atZone(zone), rawNow.atZone(zone)).getSeconds();
            if (live > 0) {
                pausedSec += live;   // garde-fou contre un paused_at futur (skew d'horloge)
            }
        }
        return pausedSec;
    }

    /**
     * Instant absolu prédit du début d'un passage (ADR-0012 §2). {@code debutCreneau}
     * est déjà ancré sur {@code launched_at} (ADR-0010) en temps effectif ; pour le
     * projeter en heure murale il faut <b>rajouter</b> le temps de pause — l'inverse
     * exact d'{@link #effectiveNow} (qui le retranche). Démonstration : le passage
     * démarre quand {@code effectiveNow == debutCreneau}, c.-à-d.
     * {@code rawNow − pause == debutCreneau}, soit {@code rawNow == debutCreneau + pause}.
     * Pendant une pause, {@code debutPrevu} recule poll après poll → le compte à
     * rebours se fige naturellement (et {@code enPause} le confirme côté client).
     */
    private LocalDateTime debutPrevu(Rotation rotation, ExamServiceClient.ExamTiming timing,
                                     LocalDateTime rawNow) {
        return rotation.getDebutCreneau().plusSeconds(pauseSeconds(timing, rawNow));
    }

    /** Durée de station : config examen si disponible, sinon repli constant. */
    private int dureeMinFor(ExamServiceClient.ExamTiming timing) {
        Integer d = timing.dureeStationMin();
        return (d != null && d > 0) ? d : DUREE_STATION_MIN;
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

    private List<PlanningCellResponse> buildPlanning(List<Rotation> rotations,
                                                     LocalDateTime rawNow,
                                                     Map<Long, ExamServiceClient.ExamTiming> timingByExam) {
        // Le filtre jour J reste sur l'heure murale du jour calendaire réel.
        // Seul le statut de chaque cellule utilise le temps effectif.
        List<PlanningCellResponse> cells = rotations.stream()
                .filter(r -> r.getDebutCreneau() != null)
                .filter(r -> r.getDebutCreneau().toLocalDate()
                        .equals(rawNow.toLocalDate()))
                .map(r -> {
                    ExamServiceClient.ExamTiming timing = timingFor(r, timingByExam);
                    return PlanningCellResponse.builder()
                            .heure(r.getDebutCreneau().format(TIME_FMT))
                            .lotNumero(getLotNumeroPourRotation(r))
                            .statut(mapRotationStatutToPlanningStatut(
                                    r, effectiveNow(timing, rawNow), dureeMinFor(timing)))
                            .build();
                })
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
                                                Long stationId, Long evaluateurId) {
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

    private Notation createNotation(RotationAssignment assignment,
                                    Long stationId, Long grilleId) {
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
     * Recalcule le score final en appliquant les pondérations depuis l'exam-service.
     *
     * Formule identique à ScoreUtils.calculerScore (Flutter) :
     *   BINAIRE   → score += valeur × pondération
     *   NUMERIQUE → score += valeur  (déjà bornée à valeurMax côté Flutter)
     *
     * Fallback si exam-service indisponible : somme des valeurs brutes (sans pondération).
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

        final boolean ponderationsDisponibles = !itemInfos.isEmpty();
        if (!ponderationsDisponibles) {
            log.warn("Pondérations indisponibles (grille={}) — score calculé en fallback",
                    notation.getGrilleId());
        }

        float score = 0f;
        for (NotationItem ni : items) {
            if (ni.getValeur() == null) continue;
            float valeur = ni.getValeur();
            if (ponderationsDisponibles) {
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
                ponderationsDisponibles ? "" : " (fallback)");
    }

    // =========================================================================
    // FIX 6 — Broadcast WebSocket (BF6.1)
    // =========================================================================

    /**
     * Publie le score d'un étudiant sur /topic/stations/{stationId}/scores.
     * Reçu par GradingBloc (GradingWsScoreReceived) côté Flutter.
     */
    private void broadcastScore(Notation notation, Long stationId) {
        if (notation.getAssignment() == null
                || notation.getAssignment().getParticipation() == null
                || notation.getAssignment().getParticipation().getEtudiant() == null) {
            log.warn("Broadcast score ignoré — chaîne notation→étudiant incomplète (notation={})",
                    notation.getId());
            return;
        }

        Long   etudiantId = notation.getAssignment().getParticipation().getEtudiant().getId();
        String topic      = String.format(TOPIC_SCORES, stationId);

        ScoreUpdateMessage message = ScoreUpdateMessage.builder()
                .etudiantId(etudiantId)
                .stationId(stationId)
                .score(notation.getScore_final())
                .verrouille(Boolean.TRUE.equals(notation.getVerouillee()))
                .build();

        messagingTemplate.convertAndSend(topic, message);
        log.debug("WS broadcast {} → etudiant={} score={} verrouille={}",
                topic, etudiantId, notation.getScore_final(), notation.getVerouillee());
    }

    /**
     * Publie le changement de statut d'un lot sur /topic/lots/{lotId}/status.
     * Reçu par WebSocketService (onLotStatusUpdate) côté Flutter.
     */
    private void broadcastLotStatus(Long lotId, String statut) {
        String topic = String.format(TOPIC_LOT, lotId);
        LotStatusMessage message = LotStatusMessage.builder()
                .lotId(lotId)
                .statut(statut)
                .build();
        messagingTemplate.convertAndSend(topic, message);
        log.debug("WS broadcast {} → statut={}", topic, statut);
    }

    // =========================================================================
    // MÉTHODES PRIVÉES — Utilitaires
    // =========================================================================

    private String mapRotationStatutToPlanningStatut(Rotation rotation,
                                                     LocalDateTime maintenant, int dureeMin) {
        if (rotation.getStatut() == RotationStatus.TERMINE) {
            return "TERMINE";
        }
        LocalDateTime debut     = rotation.getDebutCreneau();
        LocalDateTime finReelle = debut.plusMinutes((long) dureeMin + GRACE_PERIOD_MIN);

        if (maintenant.isBefore(debut)) {
            return "A_VENIR";
        }
        if (!maintenant.isAfter(finReelle)) {
            return "A_VENIR";
        }
        return "TERMINE";
    }

    private int getLotNumeroPourRotation(Rotation rotation) {
        if (rotation.getStudentGroup() == null
                || rotation.getStudentGroup().getLot() == null) return 0;
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