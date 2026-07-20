package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
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
    private final IStudentGroupRepository        studentGroupRepository;
    private final ExamServiceClient              examServiceClient;

    /**
     * ADR-0015 — définition figée dans {@code scoring_db}. Remplace les lectures réseau pour le
     * <b>nom de station</b> et les <b>pondérations d'items</b> : ces valeurs sont immuables une fois
     * l'examen {@code EN_COURS}, donc les refetcher n'apportait rien et coûtait trois défaillances
     * silencieuses pendant une panne d'exam-service (reproduites le 2026-07-20).
     */
    private final ExamDefinitionSnapshotService  examDefinitionSnapshot;

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

    // =========================================================================
 // 2bis. DÉTAIL D'UN GROUPE — scopé par rotationId (remplace l'ancien
// (stationId, lotNumero) ambigu : un évaluateur reçoit PLUSIEURS rotations
// pour UN SEUL lot (une par groupe qui passe à sa station), donc
// (stationId, lotNumero) ne désignait pas un groupe précis → findFirst()
// renvoyait toujours le même, bloquant la notation dès le 2e groupe.
// =========================================================================

    @Transactional(readOnly = true)
    public LotDetailResponse getGroupeDetail(Long rotationId, Long evaluateurId) {
        Rotation rotation = rotationRepository.findById(rotationId)
                .orElseThrow(() -> new ResourceNotFoundException("Rotation introuvable : " + rotationId));
        verifierProprietaire(rotation, evaluateurId);
        return toGroupeDetailResponse(rotation);
    }

    @Transactional(readOnly = true)
    public LotDetailResponse getGroupeSuivant(Long rotationId, Long evaluateurId) {
        Rotation courante = rotationRepository.findById(rotationId)
                .orElseThrow(() -> new ResourceNotFoundException("Rotation introuvable : " + rotationId));
        verifierProprietaire(courante, evaluateurId);
        Rotation suivante = rotationRepository
                .findFirstByEvaluateurIdAndDebutCreneauAfterOrderByDebutCreneauAsc(
                        evaluateurId, courante.getDebutCreneau())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Aucun groupe suivant : c'était la dernière rotation planifiée pour cet évaluateur."));
        return toGroupeDetailResponse(suivante);
    }

    private void verifierProprietaire(Rotation rotation, Long evaluateurId) {
        if (!evaluateurId.equals(rotation.getEvaluateurId())) {
            throw new AccessDeniedException("Cette rotation n'est pas assignée à cet évaluateur.");
        }
    }

    private LotDetailResponse toGroupeDetailResponse(Rotation rotation) {
        Lot lot = (rotation.getStudentGroup() != null) ? rotation.getStudentGroup().getLot() : null;
        if (lot == null) {
            throw new ResourceNotFoundException("Rotation " + rotation.getId() + " sans groupe/lot associé.");
        }
        int totalGroupes = studentGroupRepository.findByLotId(lot.getId()).size();
        int numeroGroupe = rotation.getStudentGroup().getNumeroGroupe();

        List<RotationAssignment> assignments =
                rotationAssignmentRepository.findByRotationId(rotation.getId());

        List<LotDetailResponse.EtudiantLotResponse> etudiants = assignments.stream()
                .filter(a -> a.getParticipation() != null && a.getParticipation().getEtudiant() != null)
                .map(a -> {
                    ExamenParticipation p = a.getParticipation();
                    return LotDetailResponse.EtudiantLotResponse.builder()
                            .id(p.getEtudiant().getId())
                            .nom(p.getEtudiant().getNom())
                            .prenom(p.getEtudiant().getPrenom())
                            // #FIX : présence par ROTATION (assignment), plus par participation
                            .absent(!Boolean.TRUE.equals(a.getPresenceConfirmee()))
                            .verrouille(isNotationVerrouillée(a.getId()))
                            .notationItems(loadNotationItems(a.getId()))
                            .build();
                })
                .collect(Collectors.toList());

        return LotDetailResponse.builder()
                .id(rotation.getId())                // id = rotation (pas lot)
                .numero(numeroGroupe)                // numéro du GROUPE (1..K), pas du lot
                .total(totalGroupes)                 // nombre total de groupes du lot
                .valide(rotation.getStatut() == RotationStatus.TERMINE)
                .etudiants(etudiants)
                .build();
    }

    private List<LotDetailResponse.NotationItemResponse> loadNotationItems(Long assignmentId) {
        return notationRepository.findByAssignmentId(assignmentId)
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
        ExamenParticipation participation = resolverParticipation(request.getEtudiantId(), request.getStationId());

        // ADR-0015 — garde feuille LOCALE et INCONDITIONNELLE. L'ancienne version interrogeait
        // exam-service et se désactivait elle-même sur réponse vide (`!isEmpty()`) : pendant une
        // panne, un critère PARENT devenait notable, et sa ligne se double-comptait ensuite
        // définitivement dans recalculerScoreFinal. Le snapshot EST l'ensemble des items notables :
        // une absence signifie « non notable », il n'y a donc plus de cas « je ne sais pas ».
        Map<Long, ExamItemSnapshot> definition =
                examDefinitionSnapshot.resolveItems(participation.getExamen_id(), request.getGrilleId());
        if (!definition.containsKey(request.getItemId())) {
            throw new BusinessException(
                    "L'item " + request.getItemId() + " n'est pas un critère notable de la grille "
                            + request.getGrilleId() + " (critère parent, ou absent de la définition "
                            + "figée au lancement — ADR-0015).");
        }
        // #203 : lookup scopé (participation, station) — une participation a un
        // assignment par station, donc un lookup par participation seule crasherait.
        RotationAssignment assignment = rotationAssignmentRepository
                .findByParticipationIdAndStationId(participation.getId(), request.getStationId())
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
        RotationAssignment assignment = rotationAssignmentRepository
                .findByParticipationIdAndStationId(participation.getId(), stationId)
                .orElseGet(() -> createAssignment(participation, stationId, evaluateurId));
        Notation notation = notationRepository.findByAssignmentId(assignment.getId())
                .orElseGet(() -> createNotation(assignment, stationId, request.getGrilleId()));

        if (request.isAbsent()) {
            notationItemRepository.deleteAll(notationItemRepository.findByNotationId(notation.getId()));
            notation.setScore_final(0.0f);
        }
        notation.setVerouillee(true);
        notationRepository.save(notation);

        // #FIX multi-station : l'absence saisie ici concerne CETTE station
        // (cette rotation), pas l'examen entier. ExamenParticipation n'a qu'une
        // seule ligne par (étudiant, examen) — un seul flag est_present ne peut
        // pas représenter 4 présences différentes (une par station). La bonne
        // place est RotationAssignment.presenceConfirmee. Participation.est_present
        // reste piloté uniquement par LotAssignmentService.markPresence (l'appel
        // de présence du lot le jour J) : question différente ("venu à l'examen ?").
        assignment.setPresenceConfirmee(!request.isAbsent());
        rotationAssignmentRepository.save(assignment);

        participation.setCommentaire(request.getCommentaire());
        // #212 — note AGRÉGÉE cross-station. ExamenParticipation n'a qu'UNE colonne
        // note, mais un étudiant passe N stations (N Notation.score_final). Écrire
        // ici le score d'UNE station y écrasait celui des autres (clobber #212, la
        // raison pour laquelle setNote a été retiré). On y stocke donc la SOMME des
        // score_final de toutes les stations DÉJÀ notées de cette participation —
        // exactement la valeur que ExamenResultDTO.totalScore recompose à la volée,
        // et la seule lecture de ParticipationDTO.note (onglet Étudiants côté web).
        participation.setNote(sommeScoresParticipation(participation.getId()));
        participationRepository.save(participation);

        broadcastScore(notation, stationId);
    }

    /**
     * Somme des {@code score_final} de toutes les notations d'une participation
     * (une par station de son circuit). Recalculée à chaque validation de station
     * — le total grandit au fur et à mesure que les stations sont verrouillées.
     * Aucune notation ⇒ {@code null} (pas encore noté ≠ zéro).
     */
    private Float sommeScoresParticipation(Long participationId) {
        List<Notation> notations = notationRepository.findByParticipationId(participationId);
        if (notations.isEmpty()) return null;
        float total = 0f;
        for (Notation n : notations) {
            if (n.getScore_final() != null) total += n.getScore_final();
        }
        return total;
    }

    public void validerLot(Long lotId, Long evaluateurId) {
        Lot lot = lotRepository.findById(lotId).orElseThrow(() -> new ResourceNotFoundException("Lot introuvable"));

        // #211 — cascade NEUTRALISÉE. L'ancienne version forçait TOUTES les
        // rotations du lot à TERMINE : un admin clôturant un lot terminait ainsi
        // de force les stations d'autres évaluateurs encore en cours de notation
        // (perte de données silencieuse). ADR-0014 §4 : le statut du lot se DÉRIVE
        // de l'état réel des rotations — on ne l'IMPOSE jamais, et on n'écrit
        // AUCUN statut de rotation ici. Ce point de terminaison "Valider lot"
        // (réservé admin/responsable) n'est donc plus qu'un recalcul d'oversight.
        long restantes = rotationRepository.countByStudentGroup_Lot_IdAndStatutNot(lotId, RotationStatus.TERMINE);
        LotStatus derive = (restantes == 0) ? LotStatus.TERMINE : LotStatus.EN_COURS;
        lot.setStatut(derive);
        lotRepository.save(lot);
        broadcastLotStatus(lotId, derive.name());
    }

    // =========================================================================
// VALIDER GROUPE — remplace "Valider lot" côté évaluateur.
// Verrouille toutes les notations du groupe COURANT (cette rotation), marque
// la rotation TERMINE, puis vérifie si c'était la DERNIÈRE rotation du lot
// (tous groupes × toutes stations) : si oui, clôture automatiquement le lot.
// =========================================================================
    public void validerGroupe(Long rotationId, Long evaluateurId) {
        Rotation rotation = rotationRepository.findById(rotationId)
                .orElseThrow(() -> new ResourceNotFoundException("Rotation introuvable : " + rotationId));
        verifierProprietaire(rotation, evaluateurId);

        if (rotation.getStatut() == RotationStatus.TERMINE) {
            throw new BusinessException("Ce groupe est déjà validé pour cette station.");
        }

        // Filet de sécurité : verrouille toute notation pas encore verrouillée
        // (normalement déjà fait étudiant par étudiant via /valider).
        List<RotationAssignment> assignments = rotationAssignmentRepository.findByRotationId(rotationId);
        for (RotationAssignment a : assignments) {
            notationRepository.findByAssignmentId(a.getId()).ifPresent(n -> {
                if (!Boolean.TRUE.equals(n.getVerouillee())) {
                    n.setVerouillee(true);
                    notationRepository.save(n);
                }
            });
        }

        rotation.setStatut(RotationStatus.TERMINE);
        rotationRepository.save(rotation);

        Lot lot = rotation.getStudentGroup() != null ? rotation.getStudentGroup().getLot() : null;
        if (lot == null) return;

        broadcastLotStatus(lot.getId(), "EN_COURS"); // refresh dashboard : groupe suivant dispo

        if (rotationRepository.countByStudentGroup_Lot_IdAndStatutNot(lot.getId(), RotationStatus.TERMINE) == 0) {
            lot.setStatut(LotStatus.TERMINE);
            lotRepository.save(lot);
            broadcastLotStatus(lot.getId(), "TERMINE");
            log.info("Lot {} : toutes les rotations sont TERMINE — lot clôturé automatiquement.", lot.getId());
        }
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
                            .id(rotation.getId())                 // ← FIX : id de rotation (groupe courant)
                            .lotId(lotLie != null ? lotLie.getId() : null)   // ← nouveau champ, pour le WS
                            .groupeNumero(rotation.getStudentGroup() != null
                                    ? rotation.getStudentGroup().getNumeroGroupe() : 0)
                            .stationId(rotation.getStationId())
                            // ADR-0015 : nom figé, jamais le repli « Station <id> » — une panne
                            // d'exam-service ne doit plus fabriquer un intitulé plausible mais faux.
                            .stationNom(examDefinitionSnapshot.resolveStationNom(
                                    lotLie != null ? lotLie.getExamenId() : null,
                                    rotation.getStationId()))
                            .statut(statut)
                            .heureDebut(rotation.getDebutCreneau().format(TIME_FMT))
                            .heureFin(rotation.getDebutCreneau().plusMinutes(dureeMin).format(TIME_FMT))
                            .dureeStationMin(dureeMin)
                            .nbEtudiants((int) rotationAssignmentRepository.countByRotationId(rotation.getId()))
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

    /**
     * ADR-0015 — le score est recalculé <b>uniquement</b> à partir de la définition figée.
     *
     * <p>L'ancienne version tolérait un {@code info == null} et notait alors l'item à sa valeur
     * brute : pendant une panne d'exam-service, un item {@code valeur 1 × pondération 5} valait
     * <b>1 au lieu de 5</b>, et ce {@code score_final} faux était persisté puis diffusé en WebSocket
     * sans la moindre erreur. Un item inconnu fait désormais échouer le calcul — on préfère refuser
     * la note plutôt qu'en enregistrer une fausse.
     */
    private void recalculerScoreFinal(Notation notation) {
        List<NotationItem> items = notationItemRepository.findByNotationId(notation.getId());
        Map<Long, ExamItemSnapshot> definition =
                examDefinitionSnapshot.resolveItems(examenIdDe(notation), notation.getGrilleId());
        float score = 0f;
        for (NotationItem ni : items) {
            score += examDefinitionSnapshot.weigh(definition, ni.getItemId(), ni.getValeur());
        }
        notation.setScore_final(score);
        notationRepository.save(notation);
    }

    /** Chemin {@code Notation → assignment → participation → examen_id} (ADR-0015). */
    private Long examenIdDe(Notation notation) {
        return (notation.getAssignment() != null && notation.getAssignment().getParticipation() != null)
                ? notation.getAssignment().getParticipation().getExamen_id()
                : null;
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

    private boolean isNotationVerrouillée(Long assignmentId) {
        return notationRepository.findByAssignmentId(assignmentId)
                .map(n -> Boolean.TRUE.equals(n.getVerouillee())).orElse(false);
    }
}