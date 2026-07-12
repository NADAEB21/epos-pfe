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
 * assignés à un évaluateur. Chaque évaluateur est fixé sur sa station ; le lot
 * « appartient » à l'évaluateur de la station qu'il occupera au créneau t=0.
 *
 * <p>Le circuit utilise un carré latin :
 * <ul>
 *   <li>K stations → K student groups → K créneaux.</li>
 *   <li>Une {@link Rotation} par (station × créneau) avec l'évaluateur lié.</li>
 *   <li>Le début de vague est décalé par les circuits précédents :
 *       {@code examStart + (m−1)·K·slot}, où {@code slot = duree + battement} (ADR-0012).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RotationGenerationService {

    private static final LocalTime DEFAULT_START          = LocalTime.of(9, 0);
    private static final int       DEFAULT_DUREE          = 15;
    private static final int       DEFAULT_BATTEMENT_MIN  = 0;
    private static final int       DEFAULT_CAPACITE       = 4;

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

        // Nettoyage avant génération
        wipeLotGroups(lotId);

        LocalTime start    = exam.heureDebut() != null ? exam.heureDebut() : DEFAULT_START;
        LocalDate date     = exam.dateExamen();
        int       duree    = exam.dureeStationMin() != null ? exam.dureeStationMin() : DEFAULT_DUREE;
        int       capacite = exam.nbEtudiantsParStation() != null ? exam.nbEtudiantsParStation() : DEFAULT_CAPACITE;

        // ADR-0012 : slot = durée + battement
        int battement = exam.tempsBattementMin() != null ? exam.tempsBattementMin() : DEFAULT_BATTEMENT_MIN;
        int slot = duree + battement;

        // Ancre temporelle (ADR-0010)
        LocalDateTime examStart = exam.launchedAt() != null
                ? exam.launchedAt()
                : LocalDateTime.of(date, start);

        int waveIndex = (lot.getNumeroLot() != null ? lot.getNumeroLot() : 1) - 1;
        LocalDateTime waveStart = examStart.plusMinutes((long) waveIndex * k * slot);

        // Partition des étudiants présents en K groupes
        List<List<ExamenParticipation>> groupStudents = new ArrayList<>();
        for (int g = 0; g < k; g++) {
            groupStudents.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            groupStudents.get(i % k).add(present.get(i));
        }

        int    maxGroupSize  = (int) Math.ceil((double) n / k);
        String avertissement = maxGroupSize > capacite
                ? "Capacité dépassée : " + maxGroupSize + " étudiants/station"
                : null;

        // Création des StudentGroups
        List<StudentGroup> groups = new ArrayList<>();
        for (int g = 0; g < k; g++) {
            StudentGroup sg = new StudentGroup();
            sg.setNumeroGroupe(g + 1);
            sg.setLot(lot);
            groups.add(studentGroupRepository.save(sg));
        }

        // Carré latin
        int  rotationCount  = 0;
        int  assignmentCount = 0;

        // FIX Dashboard — Identifie l'évaluateur qui commence avec le groupe 1
        Long evaluateurPrincipal = resolveEvaluateur(stations, 0);

        for (int t = 0; t < k; t++) {
            LocalDateTime creneau = waveStart.plusMinutes((long) t * slot);
            for (int g = 0; g < k; g++) {
                int stationIndex = (g + t) % k;
                ExamGenerationView.StationView station = stations.get(stationIndex);
                Long evaluateurId = resolveEvaluateur(stations, stationIndex);

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

        // FIX Dashboard — Propagation de l'évaluateur sur le lot
        if (evaluateurPrincipal != null && lot.getEvaluateurId() == null) {
            lot.setEvaluateurId(evaluateurPrincipal);
            lotRepository.save(lot);
            log.debug("Lot {} : evaluateurId propagé = {}", lotId, evaluateurPrincipal);
        }

        return new GenerationResult(1, k, k, k, rotationCount, assignmentCount,
                n, absents, avertissement);
    }

    private Long resolveEvaluateur(List<ExamGenerationView.StationView> stations, int index) {
        ExamGenerationView.StationView s = stations.get(index);
        return (s.evaluateurIds() != null && !s.evaluateurIds().isEmpty())
                ? s.evaluateurIds().get(0)
                : null;
    }

    private void wipeLotGroups(Long lotId) {
        List<StudentGroup> groups = studentGroupRepository.findByLotId(lotId);
        studentGroupRepository.deleteAll(groups);
    }
}