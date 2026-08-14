package tn.epos.scoring_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

    /**
     * ADR-0015 — définition figée dans {@code scoring_db}. Remplace les lectures réseau pour le
     * <b>nom de station</b> et les <b>pondérations d'items</b> : ces valeurs sont immuables une fois
     * l'examen {@code EN_COURS}, donc les refetcher n'apportait rien et coûtait trois défaillances
     * silencieuses pendant une panne d'exam-service (reproduites le 2026-07-20).
     */
    private final ExamDefinitionSnapshotService  examDefinitionSnapshot;

    /** Horloge injectable ADR-0010. */
    private final Clock clock;

    // Plus de garde de matière ici, et ce n'est pas un oubli : `validerLot` était le seul acte
    // RESP/ADMIN de cette classe, et il est supprimé. Tout ce qui reste est un acte d'ÉVALUATEUR,
    // borné par `verifierProprietaire` / `verifierAffectationStation` (#213) — strict, sans
    // exemption de rôle. Le périmètre par matière n'y ajouterait rien.

    /** BF6.1 — Template STOMP pour le push WebSocket. */
    private final SimpMessagingTemplate          messagingTemplate;

    // ── Constantes ────────────────────────────────────────────────────────────

    private static final DateTimeFormatter TIME_FMT            = DateTimeFormatter.ofPattern("HH:mm");
    private static final String            STATUT_TERMINEE     = "TERMINEE";
    private static final String            LOG_ETUDIANT_STATION = " station=";

    private static final int DUREE_STATION_MIN = 15;

    private static final String TOPIC_SCORES = "/topic/stations/%d/scores";
    // ADR-0014-B — destination unique, définie sur le DTO : deux copies de la même
    // destination « à garder identiques » ont déjà divergé ailleurs dans ce service.
    private static final String TOPIC_LOT    = LotStatusMessage.TOPIC;

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

    /**
     * #244 — grille figée (ADR-0015), servie depuis scoring-service pour que
     * l'écran de notation mobile n'appelle plus jamais exam-service.
     */
    @Transactional(readOnly = true)
    public GrilleSnapshotDTO getGrilleStation(Long stationId, Long evaluateurId) {
        // Réutilise le garde #213 existant plutôt que d'en réécrire un second
        // (même raison que #218 : deux mécanismes de propriété finiraient par diverger).
        verifierAffectationStation(evaluateurId, stationId);

        Long examenId = rotationRepository
                .findFirstByEvaluateurIdAndStationIdOrderByIdDesc(evaluateurId, stationId)
                .map(r -> r.getStudentGroup().getLot().getExamenId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Aucune rotation pour résoudre l'examen de la station " + stationId));

        ExamGrilleSnapshot snap = examDefinitionSnapshot.resolveGrille(examenId, stationId);
        return GrilleSnapshotDTO.fromEntity(snap, objectMapper);
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

    /**
     * #209 — ouvrir l'écran d'un groupe DÉMARRE son minuteur : premier accès du propriétaire
     * à une rotation EN_COURS ⇒ {@code debutReel} est horodaté (une fois). C'est le
     * « Poursuivre la notation » de Nada — le plancher s'ancre sur un fait observé, plus
     * jamais sur le créneau planifié (constaté : « 12:51 » restants sur une station de 2 min).
     */
    @Transactional
    public LotDetailResponse getGroupeDetail(Long rotationId, Long evaluateurId) {
        Rotation rotation = rotationRepository.findById(rotationId)
                .orElseThrow(() -> new ResourceNotFoundException("Rotation introuvable : " + rotationId));
        verifierProprietaire(rotation, evaluateurId);
        marquerDebutReel(rotation);
        return toGroupeDetailResponse(rotation);
    }

    /**
     * #209 — « Groupe suivant » est désormais L'ACTE D'AVANCER, découplé de la validation :
     * il ouvre le rang suivant ({@code EN_COURS} + {@code debutReel}) et le renvoie.
     * Auparavant c'était {@code validerGroupe} qui ouvrait le rang suivant — verrouiller et
     * avancer étaient soudés, si bien qu'un évaluateur qui validait puis quittait l'écran se
     * retrouvait « déplacé » au groupe suivant à son retour (grille vide — vécu par Nada).
     * Règle : seul le clic explicite « Groupe suivant » avance.
     */
    @Transactional
    public LotDetailResponse avancerGroupe(Long rotationId, Long evaluateurId) {
        Rotation courante = rotationRepository.findById(rotationId)
                .orElseThrow(() -> new ResourceNotFoundException("Rotation introuvable : " + rotationId));
        verifierProprietaire(courante, evaluateurId);

        // #248 — séquencé sur ordrePassage et borné à (cette station, ce lot), exactement comme
        // la garde du bouton. L'horloge ne séquence rien.
        Rotation suivante = rotationSuivante(courante)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Aucun groupe suivant : c'était le dernier passage de cette station pour ce lot."));

        if (suivante.getStatut() == RotationStatus.EN_ATTENTE) {
            suivante.setStatut(RotationStatus.EN_COURS);
            log.info("Station {} : groupe suivant (rotation {}, rang {}) ouvert par l'évaluateur.",
                    suivante.getStationId(), suivante.getId(), suivante.getOrdrePassage());
        }
        marquerDebutReel(suivante);
        rotationRepository.save(suivante);
        return toGroupeDetailResponse(suivante);
    }

    /** Écrit {@code debutReel} UNE fois, seulement sur une rotation réellement EN_COURS. */
    private void marquerDebutReel(Rotation rotation) {
        if (rotation.getStatut() == RotationStatus.EN_COURS && rotation.getDebutReel() == null) {
            rotation.setDebutReel(LocalDateTime.now(clock));
            rotationRepository.save(rotation);
        }
    }

    /**
     * #248 — LE passage suivant : même station, même lot, rang immédiatement supérieur.
     * Source unique pour {@code getGroupeSuivant}, pour le drapeau
     * {@code groupeSuivantDisponible} envoyé au client, et pour l'ouverture faite par
     * {@code validerGroupe} — afin que le bouton, la navigation et l'écriture ne puissent
     * plus diverger.
     */
    private Optional<Rotation> rotationSuivante(Rotation courante) {
        Lot lot = (courante.getStudentGroup() != null) ? courante.getStudentGroup().getLot() : null;
        if (lot == null) return Optional.empty();
        return rotationRepository
                .findFirstByStationIdAndStudentGroup_Lot_IdAndOrdrePassageGreaterThanOrderByOrdrePassageAsc(
                        courante.getStationId(), lot.getId(), courante.getOrdrePassage());
    }

    private void verifierProprietaire(Rotation rotation, Long evaluateurId) {
        if (!evaluateurId.equals(rotation.getEvaluateurId())) {
            throw new AccessDeniedException("Cette rotation n'est pas assignée à cet évaluateur.");
        }
    }

    /**
     * #213 — garde du chemin d'ÉCRITURE : on ne note que sur SA station.
     *
     * <p>Le garde existait déjà, mais sur le mauvais chemin :
     * {@code createAssignment} exige une rotation {@code (évaluateur, station)}
     * et lève sinon — sauf qu'il ne s'exécute que si l'assignment n'existe pas
     * encore. Or {@code presence-et-demarrer} les crée tous au démarrage du lot.
     * En conditions réelles le chemin froid n'est donc JAMAIS emprunté, et le
     * contrôle ne tournait jamais. Prouvé en direct : l'évaluateur 6 a noté 9.5
     * sur la station 87, qui appartient au 3.
     *
     * <p><b>Le prédicat est « avoir une rotation sur cette station », pas
     * l'égalité avec un titulaire.</b> Une station peut compter PLUSIEURS
     * évaluateurs (l'affectation est une liste), donc une égalité stricte
     * refuserait un co-titulaire légitime. C'est aussi ce qui rend le garde
     * compatible avec la suppléance (ADR-0017) : celle-ci réaffecte
     * {@code rotation.evaluateurId}, donc le remplaçant passe le garde
     * immédiatement, sans traitement particulier.
     */
    private void verifierAffectationStation(Long evaluateurId, Long stationId) {
        if (!rotationRepository.existsByEvaluateurIdAndStationId(evaluateurId, stationId)) {
            throw new AccessDeniedException(
                    "Vous n'êtes pas affecté à la station " + stationId
                            + " : la notation d'un étudiant appartient à l'évaluateur qui tient la "
                            + "station. En cas de remplacement, le responsable doit d'abord vous "
                            + "affecter à cette station (ADR-0017).");
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
                    // #212 — une seule lecture de la Notation par assignment : sert le verrou
                    // ET le commentaire (désormais par-station, et enfin REJOUÉ au client —
                    // ce champ de réponse existait depuis le début sans jamais être rempli).
                    Optional<Notation> notation = notationRepository.findByAssignmentId(a.getId());
                    return LotDetailResponse.EtudiantLotResponse.builder()
                            .id(p.getEtudiant().getId())
                            .nom(p.getEtudiant().getNom())
                            .prenom(p.getEtudiant().getPrenom())
                            // #FIX : présence par ROTATION (assignment), plus par participation
                            .absent(!Boolean.TRUE.equals(a.getPresenceConfirmee()))
                            .verrouille(notation.map(n -> Boolean.TRUE.equals(n.getVerouillee()))
                                    .orElse(false))
                            .commentaire(notation.map(Notation::getCommentaire).orElse(null))
                            .notationItems(loadNotationItems(a.getId()))
                            .build();
                })
                .collect(Collectors.toList());

        return LotDetailResponse.builder()
                .id(rotation.getId())                // id = rotation (pas lot)
                .numero(numeroGroupe)                // numéro du GROUPE (1..K), pas du lot
                .total(totalGroupes)                 // nombre total de groupes du lot
                .valide(rotation.getStatut() == RotationStatus.TERMINE)
                // #248 — le client ne doit PAS redéduire ceci de numero/total : le carré latin
                // fait tourner les groupes, donc « je suis le groupe K » ne veut pas dire « je
                // suis le dernier passage de cette station ».
                .groupeSuivantDisponible(rotationSuivante(rotation).isPresent())
                // #209 — l'ancre OBSERVÉE du minuteur plancher (jamais le créneau planifié).
                .debutReel(rotation.getDebutReel())
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
        // #213 — on ne note que sur SA station. Voir verifierAffectationStation :
        // le contrôle existant ne vivait que sur le chemin froid, jamais emprunté.
        verifierAffectationStation(evaluateurId, request.getStationId());
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
        // #213 — on ENREGISTRE l'auteur au lieu de le déduire de la rotation.
        // La déduction désignait le propriétaire de la station, donc la mauvaise
        // personne dès que quelqu'un d'autre écrivait.
        notation.setSaisiPar(evaluateurId);
        notationRepository.save(notation);

        recalculerScoreFinal(notation);
        broadcastScore(notation, request.getStationId());
    }

    public void validerEtudiant(Long etudiantId, Long stationId, Long evaluateurId, ValiderEtudiantRequest request) {
        // #213 — verrouiller la note d'un étudiant est une écriture, et la plus
        // définitive : même garde que la saisie.
        verifierAffectationStation(evaluateurId, stationId);
        ExamenParticipation participation = resolverParticipation(etudiantId, stationId);
        RotationAssignment assignment = rotationAssignmentRepository
                .findByParticipationIdAndStationId(participation.getId(), stationId)
                .orElseGet(() -> createAssignment(participation, stationId, evaluateurId));
        Notation notation = notationRepository.findByAssignmentId(assignment.getId())
                .orElseGet(() -> createNotation(assignment, stationId, request.getGrilleId()));

        // #297 — un second appel sur une notation DÉJÀ verrouillée ne doit
        // jamais réécrire silencieusement verrouillePar/commentaire, ni
        // retraverser la garde de complétude. Seul le canal audité (réajustement,
        // ADR-0013 Part 2 — NotationReajustementService) peut changer une
        // notation verrouillée. Ce trou préexistait à #297 ; comme la méthode
        // est déjà retouchée ici, c'est le bon moment pour le fermer plutôt que
        // de laisser cohabiter une porte silencieuse à côté du canal audité.
        if (Boolean.TRUE.equals(notation.getVerouillee())) {
            throw new BusinessException(
                    "Notation déjà verrouillée pour " + nomEtudiant(participation.getEtudiant())
                            + ". Utilisez le canal de réajustement (réclamation) pour la modifier.");
        }

        if (request.isAbsent()) {
            notationItemRepository.deleteAll(notationItemRepository.findByNotationId(notation.getId()));
            notation.setScore_final(0.0f);
        } else {
            // #297 — refus DUR avant tout verrouillage. L'absence (branche
            // ci-dessus) est la SEULE sortie qui contourne ce contrôle : elle est
            // un verdict légitime et ne doit jamais être bloquée.
            //
            // Comme toute la classe est @Transactional, une BusinessException ici
            // annule aussi la création de l'assignment/notation "coquille" faite
            // plus haut par orElseGet : rien n'est persisté, y compris pas une
            // notation fantôme non verrouillée. Fail-closed complet.
            assertCompletudeAvantVerrouillage(participation, notation, request.getGrilleId());
        }

        // #212 (dernier volet) — le commentaire suit le même chemin que la présence : il
        // concerne CETTE station, donc il vit sur Notation (l'enregistrement par
        // (participation, station)), plus jamais sur la ligne partagée ExamenParticipation
        // où « la dernière station gagnait ». En prime il est enfin REJOUÉ au mobile
        // (EtudiantLotResponse.commentaire, jamais rempli jusqu'ici).
        notation.setCommentaire(request.getCommentaire());
        notation.setVerouillee(true);
        // #213 — qui verrouille est une décision, pas une déduction : c'est
        // l'acte qui rend la note définitive côté évaluateur (ADR-0013).
        notation.setVerrouillePar(evaluateurId);
        if (notation.getSaisiPar() == null) {
            // Validation d'un étudiant noté absent : aucune saisie n'a précédé.
            notation.setSaisiPar(evaluateurId);
        }
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
     * #297 — refuse le verrouillage tant que des critères notables de la grille
     * n'ont pas de valeur saisie. N'est JAMAIS appelée pour un étudiant absent
     * (voir l'appelant) : l'absence est un verdict légitime qui ne bloque rien.
     *
     * <p>Passe TOUJOURS par {@link ExamDefinitionSnapshotService#resolveItems},
     * jamais par {@code itemSnapshotRepository.findByGrilleId} directement.
     * {@code findByGrilleId} rend une liste VIDE tant que la grille n'a jamais
     * été touchée ; un comptage sur cette liste vide lirait « 0 attendu, 0 saisi
     * → complet » — la garde laisserait passer exactement le cas qu'elle doit
     * attraper : un étudiant jamais noté. C'est la même vérité vide qui a fait
     * retirer {@code validerLot} (#315). {@code resolveItems} matérialise à la
     * demande et échoue fort si la grille ne déclare vraiment aucun critère
     * (ADR-0015) — c'est le même garde de feuille que {@code saisirNotation}.
     *
     * <p>Cas limite à connaître (pas un bug) : verrouiller à zéro critère saisi,
     * sur une grille jamais touchée, pendant une panne d'exam-service. Le refus
     * reste correct (fail-closed) mais le message sera celui d'ADR-0015
     * (« snapshot non figé ») plutôt que « il reste N critères non notés ».
     */
    private void assertCompletudeAvantVerrouillage(ExamenParticipation participation, Notation notation, Long grilleId) {
        Map<Long, ExamItemSnapshot> definition =
                examDefinitionSnapshot.resolveItems(participation.getExamen_id(), grilleId);

        Set<Long> saisis = notationItemRepository.findByNotationId(notation.getId()).stream()
                .map(NotationItem::getItemId)
                .collect(Collectors.toSet());

        Set<Long> manquants = new LinkedHashSet<>(definition.keySet());
        manquants.removeAll(saisis);

        if (!manquants.isEmpty()) {
            throw new BusinessException(
                    "Impossible de verrouiller : il reste " + manquants.size()
                            + " critère(s) non noté(s) pour " + nomEtudiant(participation.getEtudiant())
                            + ". Notez tous les critères, ou déclarez l'étudiant absent.");
        }
    }

    private String nomEtudiant(Etudiant e) {
        if (e == null) return "cet étudiant";
        String full = ((e.getPrenom() != null ? e.getPrenom() : "") + " "
                + (e.getNom() != null ? e.getNom() : "")).trim();
        return full.isEmpty() ? "cet étudiant" : full;
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

    // =========================================================================
    // « VALIDER UN LOT » — SUPPRIMÉ. Ne pas le réintroduire.
    //
    // Personne ne clôture un lot à la main : le lot se clôture TOUT SEUL. Voir la fin de
    // validerGroupe ci-dessous — dès que la dernière rotation du lot passe TERMINE, le lot
    // passe TERMINE. Le dernier évaluateur qui valide son dernier groupe ferme la vague, ce
    // qui est exactement le moment où elle est finie.
    //
    // Ce qu'était `validerLot(lotId, evaluateurId)`, et pourquoi il ne reste rien :
    //   * à l'origine il forçait TOUTES les rotations du lot à TERMINE. #211 a supprimé cette
    //     cascade : un admin « clôturant » un lot terminait de force les stations de collègues
    //     encore en train de noter — perte de données silencieuse (ADR-0014 §4 : le statut du
    //     lot se DÉRIVE de l'état réel des rotations, on ne l'IMPOSE jamais) ;
    //   * il ne restait donc qu'un recalcul de ce que validerGroupe calcule déjà ;
    //   * son paramètre `evaluateurId` n'était même plus lu ;
    //   * ZÉRO appelant : `frontend-web` ne contient aucun littéral « /valider », et côté
    //     Flutter la constante était commentée (« Remplace validerLot et validerRotation ») ;
    //   * et il était FAUX : `countByStudentGroup_Lot_IdAndStatutNot(..., TERMINE)` vaut 0
    //     quand le lot n'a AUCUNE rotation, donc l'appeler sur un lot jamais démarré le
    //     marquait TERMINE. Mesuré en direct sur un examen encore en CONFIGURE.
    //
    // Le vocabulaire restait celui d'un modèle disparu : « valider » servait à trois grains
    // (un étudiant, un groupe, un lot) alors que seuls les deux premiers sont des actes.
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

        // #297 — refus dur, PLUS de filet de sécurité silencieux. L'ancien code
        // verrouillait ici toute notation non verrouillée (ifPresent) puis passait
        // la rotation TERMINE sans condition : un étudiant sans AUCUNE notation
        // était simplement ignoré, jamais nommé. Verrouiller est maintenant un
        // acte contrôlé (validerEtudiant, cf. assertCompletudeAvantVerrouillage) :
        // une notation absente ou non verrouillée ICI signifie que l'évaluateur ne
        // l'a jamais validée — jamais qu'on peut le faire à sa place.
        List<RotationAssignment> assignments = rotationAssignmentRepository.findByRotationId(rotationId);
        List<String> sansVerdict = assignments.stream()
                .filter(a -> a.getParticipation() != null && a.getParticipation().getEtudiant() != null)
                .filter(a -> notationRepository.findByAssignmentId(a.getId())
                        .map(n -> !Boolean.TRUE.equals(n.getVerouillee()))
                        .orElse(true))
                .map(a -> nomEtudiant(a.getParticipation().getEtudiant()))
                .toList();

        if (!sansVerdict.isEmpty()) {
            throw new BusinessException(
                    "Impossible de valider le groupe : aucun verdict pour "
                            + String.join(", ", sansVerdict)
                            + ". Verrouillez chaque étudiant restant (noté ou déclaré absent) "
                            + "avant de valider le groupe.");
        }

        rotation.setStatut(RotationStatus.TERMINE);
        rotationRepository.save(rotation);

        Lot lot = rotation.getStudentGroup() != null ? rotation.getStudentGroup().getLot() : null;
        if (lot == null) return;

        // #209 — valider N'AVANCE PLUS. La version #207 ouvrait ici le rang suivant :
        // verrouiller et avancer étaient soudés, donc un évaluateur qui validait puis
        // quittait l'écran retrouvait à son retour un AUTRE groupe, grille vide (vécu par
        // Nada). Règle : valider = verrouiller, point ; seul le clic explicite « Groupe
        // suivant » ({@link #avancerGroupe}) ouvre le rang suivant. L'évaluateur reste
        // maître du rythme — y compris celui de ne pas encore avancer.
        broadcastLotStatus(lot.getId(), "EN_COURS"); // refresh dashboard

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
                    int dureeMin = dureeMinFor(timing);
                    String statut = resolveSessionStatut(rotation);
                    Lot lotLie = resolverLotDepuisRotation(rotation, lotsParId);

                    return SessionResponse.builder()
                            .id(rotation.getId())                 // ← FIX : id de rotation (groupe courant)
                            .lotId(lotLie != null ? lotLie.getId() : null)   // ← nouveau champ, pour le WS
                            .groupeNumero(rotation.getStudentGroup() != null
                                    ? rotation.getStudentGroup().getNumeroGroupe() : 0)
                            .stationId(rotation.getStationId())
                            // ADR-0015 : nom figé, jamais le repli « Station <id> » — une panne
                            // d'exam-service ne doit plus fabriquer un intitulé plausible mais faux.
                            // Variante d'AFFICHAGE : dégrade cette session-là seulement, au lieu
                            // de faire tomber tout le tableau de bord (les sessions déjà figées
                            // doivent rester utilisables pendant une panne — c'est la promesse
                            // de l'ADR). Le chemin d'ÉCRITURE, lui, reste strict.
                            .stationNom(examDefinitionSnapshot.resolveStationNomPourAffichage(
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

    /**
     * #207 / ADR-0014 — l'avancement est lu, plus déduit.
     *
     * <p>L'ancienne version calculait {@code debutCreneau + duree + 30 min de grâce} et
     * retirait d'office la session passé ce délai. C'était un <b>PLAFOND</b> : un examen qui
     * dérive de plus de 45 min voyait toutes ses sessions basculer TERMINEE alors que
     * personne n'avait rien validé, et l'évaluateur se retrouvait devant un tableau mort
     * (#238). Le statut vient désormais de la seule source qui sait où en est réellement la
     * salle : l'état persisté de la rotation, écrit à la génération (rang 1) puis à chaque
     * « Groupe suivant ».
     *
     * <p>L'horloge garde son rôle de <b>PLANCHER</b> ailleurs — {@code debutPrevu} et
     * {@code dureeStationMin} alimentent toujours le compte à rebours mobile, qui garantit à
     * l'étudiant le temps qui lui est dû. Elle ne décide simplement plus de la fin.
     */
    private String resolveSessionStatut(Rotation rotation) {
        RotationStatus statut = rotation.getStatut();
        if (statut == null) return "A_VENIR";
        return switch (statut) {
            // #209 — garde anti-impasse du découplage valider/avancer : un groupe validé
            // dont le RANG SUIVANT n'a pas encore été ouvert reste la session EN_COURS de
            // l'évaluateur. Sans cela, valider puis quitter l'écran laissait l'accueil sans
            // aucune carte active — l'évaluateur ne pouvait plus atteindre « Groupe
            // suivant » (le bouton vit dans l'écran de notation). Il revient donc LÀ OÙ IL
            // ÉTAIT : le récapitulatif verrouillé du groupe validé, d'où il avance.
            // Dès que le rang suivant est ouvert (EN_COURS), ce groupe redevient TERMINEE.
            case TERMINE   -> rotationSuivante(rotation)
                    .filter(s -> s.getStatut() == RotationStatus.EN_ATTENTE)
                    .isPresent() ? "EN_COURS" : STATUT_TERMINEE;
            case EN_COURS  -> "EN_COURS";
            case EN_ATTENTE -> "A_VENIR";
        };
    }

    private int sessionStatutOrdre(String statut) {
        return switch (statut) {
            case "EN_COURS" -> 0;
            case "A_VENIR"  -> 1;
            default         -> 2;
        };
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
                    return PlanningCellResponse.builder()
                            .heure(r.getDebutCreneau().format(TIME_FMT))
                            .lotNumero(r.getStudentGroup() != null ? r.getStudentGroup().getLot().getNumeroLot() : 0)
                            .groupeNumero(r.getStudentGroup() != null ? r.getStudentGroup().getNumeroGroupe() : null)
                            .statut(mapRotationStatutToPlanningStatut(r)).build();
                }).collect(Collectors.toList());
    }

    /**
     * #207 — même plafond d'horloge que {@link #resolveSessionStatut}, retiré de la même façon.
     *
     * <p>La cellule de planning est binaire côté client ({@code CellStatus} ne connaît que
     * TERMINE / A_VENIR / AUCUN, sans EN_COURS) : un groupe en cours reste donc affiché
     * « à venir » tant qu'il n'est pas validé, ce qui était déjà le contrat.
     */
    private String mapRotationStatutToPlanningStatut(Rotation r) {
        return r.getStatut() == RotationStatus.TERMINE ? "TERMINE" : "A_VENIR";
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

}