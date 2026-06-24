package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.common.exception.BusinessException;
import tn.epos.scoring_service.client.ExamGenerationView;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.dto.GenerationResult;
import tn.epos.scoring_service.entities.*;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;
import tn.epos.scoring_service.repositories.ILotRepository;
import tn.epos.scoring_service.repositories.IRotationAssignmentRepository;
import tn.epos.scoring_service.repositories.IRotationRepository;
import tn.epos.scoring_service.repositories.IStudentGroupRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generates the OSCE rotation plan for a single <b>lot</b> (Phase 2 — exam day,
 * after that lot's presence is marked).
 *
 * <p><b>FIX dashboard vide</b> : lors de la génération, l'évaluateur de la
 * première station du circuit est propagé sur {@code Lot.evaluateurId}. Cela
 * permet à {@code ILotRepository.findByEvaluateurId()} de retourner les lots
 * assignés à un évaluateur, ce que le dashboard Flutter exploite pour construire
 * les sessions et les statistiques.
 *
<<<<<<< HEAD
 * <p>The circuit itself is the same Latin square as #130, now scoped to one lot:
 * <ul>
 *   <li>K stations → K student groups → K créneaux; every group visits every
 *       station exactly once and no station is idle.</li>
 *   <li>One {@link Rotation} per (station × créneau) carries the station's bound
 *       évaluateur and {@code debutCreneau} = the lot's wave start + t·durée.</li>
 *   <li>One {@link RotationAssignment} per (present student × station).</li>
 * </ul>
 *
 * <p>The lot's wave start is staggered by the {@code m−1} preceding circuits,
 * i.e. {@code examStart + (m−1)·K·slot}, where {@code slot = dureeStationMin +
 * tempsBattementMin} (ADR-0012). The {@code tempsBattementMin} transition buffer
 * defaults to 0 — a zero buffer reproduces the historical back-to-back schedule.
=======
 * <p>Choix du « premier évaluateur » : dans un circuit OSCE, chaque évaluateur
 * est fixé sur sa station pour toute la journée. La relation est donc bijective
 * (station ↔ évaluateur) et le lot « appartient » à l'évaluateur de la station
 * qu'il occupera au créneau t=0 (ordrePassage=1). C'est la valeur stockée sur
 * {@code Lot.evaluateurId}.
>>>>>>> 4bc9e51 (fix dashboard evaluateur)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RotationGenerationService {

<<<<<<< HEAD
    private static final LocalTime DEFAULT_START = LocalTime.of(9, 0);
    private static final int DEFAULT_DUREE_MIN = 15;
    private static final int DEFAULT_BATTEMENT_MIN = 0;
    private static final int DEFAULT_CAPACITE = 4;
=======
    private static final LocalTime DEFAULT_START   = LocalTime.of(9, 0);
    private static final int       DEFAULT_DUREE   = 15;
    private static final int       DEFAULT_CAPACITE = 4;
>>>>>>> 4bc9e51 (fix dashboard evaluateur)

    private final ExamServiceClient               examServiceClient;
    private final IExamenParticipationRepository  participationRepository;
    private final ILotRepository                  lotRepository;
    private final IStudentGroupRepository         studentGroupRepository;
    private final IRotationRepository             rotationRepository;
    private final IRotationAssignmentRepository   assignmentRepository;

    @Transactional
    public GenerationResult generateForLot(Long lotId) {
        Lot lot = lotRepository.findById(lotId)
                .orElseThrow(() -> new BusinessException("Lot introuvable : " + lotId));

        ExamGenerationView exam = examServiceClient.getExamForGeneration(lot.getExamenId());

        if (!"EN_COURS".equals(exam.statut())) {
            throw new BusinessException(
                    "Les rotations se génèrent le jour de l'examen, une fois celui-ci lancé "
                            + "(statut EN_COURS requis ; statut actuel : " + exam.statut() + ").");
        }
        if (lot.getStatut() != LotStatus.EN_COURS) {
            throw new BusinessException(
                    "Marquez d'abord la présence du lot " + lot.getNumeroLot()
                            + " avant de générer ses rotations.");
        }

        // Stations triées par ordre d'affichage
        List<ExamGenerationView.StationView> stations = new ArrayList<>(exam.stations());
        stations.sort(Comparator.comparing(
                ExamGenerationView.StationView::ordre,
                Comparator.nullsLast(Comparator.naturalOrder())));
        int k = stations.size();
        if (k == 0) {
            throw new BusinessException(
                    "Aucune station : ajoutez au moins une station avant de générer les rotations.");
        }

        // Étudiants présents du lot
        List<ExamenParticipation> lotParticipations =
                participationRepository.findByLotId(lotId);
        List<ExamenParticipation> present = lotParticipations.stream()
                .filter(p -> !Boolean.FALSE.equals(p.getEst_present()))
                .sorted(Comparator.comparing(ExamenParticipation::getId))
                .toList();
        int n       = present.size();
        int absents = lotParticipations.size() - n;
        if (n == 0) {
            throw new BusinessException(
                    "Aucun étudiant présent dans le lot " + lot.getNumeroLot() + ".");
        }

        // Re-runnable : purge le plan précédent de ce lot uniquement
        wipeLotGroups(lotId);

        LocalTime start   = exam.heureDebut()       != null ? exam.heureDebut()       : DEFAULT_START;
        LocalDate date    = exam.dateExamen();
        int       duree   = exam.dureeStationMin()   != null ? exam.dureeStationMin()   : DEFAULT_DUREE;
        int       capacite = exam.nbEtudiantsParStation() != null
                ? exam.nbEtudiantsParStation() : DEFAULT_CAPACITE;

<<<<<<< HEAD
        // ADR-0012 : tampon de transition inter-créneau. L'unité de créneau ("slot")
        // devient (durée + battement) au lieu de la seule durée, ce qui insère un
        // intervalle entre les passages successifs ET décale les vagues d'autant.
        // battement = 0 (défaut, rétro-compatible) régénère un planning identique.
        int battement = exam.tempsBattementMin() != null ? exam.tempsBattementMin() : DEFAULT_BATTEMENT_MIN;
        int slot = duree + battement;

        // Back-to-back waves: lot m (1-based) starts after the m-1 preceding circuits.
        // ADR-0010: anchor on the ACTUAL launch instant when known (generation only
        // runs post-launch, so launched_at is normally set), so the plan and the
        // live clock share one origin and a late launch shifts all créneaux with it.
        // Fall back to the planned start (dateExamen + heureDebut) for legacy rows.
=======
        // Ancre temporelle (ADR-0010)
>>>>>>> 4bc9e51 (fix dashboard evaluateur)
        LocalDateTime examStart = exam.launchedAt() != null
                ? exam.launchedAt()
                : LocalDateTime.of(date, start);
        int waveIndex = (lot.getNumeroLot() != null ? lot.getNumeroLot() : 1) - 1;
        LocalDateTime waveStart = examStart.plusMinutes((long) waveIndex * k * slot);

        // Partition des étudiants présents en K groupes équilibrés
        List<List<ExamenParticipation>> groupStudents = new ArrayList<>();
        for (int g = 0; g < k; g++) {
            groupStudents.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            groupStudents.get(i % k).add(present.get(i));
        }
        int    maxGroupSize  = (int) Math.ceil((double) n / k);
        String avertissement = maxGroupSize > capacite
                ? "Capacité dépassée : " + maxGroupSize + " étudiants/station alors que "
                + capacite + " sont configurés. Ajoutez des stations ou réduisez l'effectif du lot."
                : null;

        // K groupes d'étudiants rattachés au lot
        List<StudentGroup> groups = new ArrayList<>();
        for (int g = 0; g < k; g++) {
            StudentGroup sg = new StudentGroup();
            sg.setNumeroGroupe(g + 1);
            sg.setLot(lot);
            groups.add(studentGroupRepository.save(sg));
        }

        // ── Carré latin : au créneau t, groupe g → station (g+t) mod K ──────
        int  rotationCount  = 0;
        int  assignmentCount = 0;

        // FIX — évaluateur de la station occupée par le groupe 0 au créneau 0
        // (station index 0, celle qui démarre le circuit). Propagé sur le lot
        // pour que findByEvaluateurId() fonctionne dans le dashboard.
        Long evaluateurPrincipal = resolveEvaluateur(stations, 0);

        for (int t = 0; t < k; t++) {
            LocalDateTime creneau = waveStart.plusMinutes((long) t * slot);
            for (int g = 0; g < k; g++) {
                ExamGenerationView.StationView station = stations.get((g + t) % k);
                Long evaluateurId = resolveEvaluateur(stations, (g + t) % k);

                Rotation rotation = new Rotation();
                rotation.setStationId(station.id());
                rotation.setEvaluateurId(evaluateurId);
                rotation.setOrdrePassage(t + 1);
                rotation.setDebutCreneau(creneau);
                rotation.setStatut(RotationStatus.EN_ATTENTE);
                rotation.setStudentGroup(groups.get(g));
                rotation = rotationRepository.save(rotation);
                rotationCount++;

                for (ExamenParticipation p : groupStudents.get(g)) {
                    RotationAssignment a = new RotationAssignment();
                    a.setRotation(rotation);
                    a.setParticipation(p);
                    a.setPresenceConfirmee(true);
                    assignmentRepository.save(a);
                    assignmentCount++;
                }
            }
        }

        // FIX — Propage l'évaluateur principal sur le lot pour le dashboard.
        // Sans cette ligne, findByEvaluateurId() retourne toujours une liste vide
        // car repartir() ne connaît pas encore les évaluateurs (assignés plus tard).
        if (evaluateurPrincipal != null && lot.getEvaluateurId() == null) {
            lot.setEvaluateurId(evaluateurPrincipal);
            lotRepository.save(lot);
            log.debug("Lot {} : evaluateurId propagé = {}", lotId, evaluateurPrincipal);
        }

        log.info("Rotations générées pour lot {} (examen {}) : {} groupes, {} rotations, "
                        + "{} assignments ({} présents, {} absents)",
                lotId, lot.getExamenId(), k, rotationCount, assignmentCount, n, absents);

        return new GenerationResult(1, k, k, k, rotationCount, assignmentCount,
                n, absents, avertissement);
    }

    // ── Utilitaires privés ────────────────────────────────────────────────────

    /** Résout l'évaluateur d'une station par son index dans la liste triée. */
    private Long resolveEvaluateur(List<ExamGenerationView.StationView> stations, int index) {
        ExamGenerationView.StationView s = stations.get(index);
        return (s.evaluateurIds() != null && !s.evaluateurIds().isEmpty())
                ? s.evaluateurIds().get(0)
                : null;
    }

    /**
     * Purge le plan existant d'un lot (groupes → rotations → assignments en cascade).
     * Le lot lui-même, ses participations et les autres lots sont conservés.
     */
    private void wipeLotGroups(Long lotId) {
        List<StudentGroup> groups = studentGroupRepository.findByLotId(lotId);
        studentGroupRepository.deleteAll(groups);
    }
}