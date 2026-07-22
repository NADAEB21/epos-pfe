package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.scoring_service.dto.dashboard.SuiviProgressionResponse;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationAssignment;
import tn.epos.scoring_service.entities.RotationStatus;
import tn.epos.scoring_service.repositories.ILotRepository;
import tn.epos.scoring_service.repositories.INotationRepository;
import tn.epos.scoring_service.repositories.IRotationAssignmentRepository;
import tn.epos.scoring_service.repositories.IRotationRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * #208 — le tableau de progression du responsable, <b>dérivé de l'état stocké</b>.
 *
 * <p>Ce service existe pour que le web n'ait plus rien à re-déduire. Le Suivi calculait jusqu'ici
 * le statut de chaque passage à partir de {@code now} et de {@code debutCreneau} : il affichait
 * donc « dépassement — créneau écoulé, encore en cours » sur des rotations qui étaient
 * {@code TERMINE} en base. La donnée juste existait et n'était pas lue. ADR-0014 vaut aussi pour
 * l'affichage : on lit l'état, on ne le devine pas.
 *
 * <p><b>Aucune horloge ici.</b> Pas de durée, pas de {@code now}, pas de dépassement. Le seul
 * élément temporel exposé est {@code Lot.ouvertA} — une ancre que le client affiche telle quelle
 * (#252), et dont rien n'est dérivé.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SuiviProgressionService {

    private final ILotRepository                lotRepository;
    private final IRotationRepository           rotationRepository;
    private final IRotationAssignmentRepository assignmentRepository;
    private final INotationRepository           notationRepository;

    @Transactional(readOnly = true)
    public SuiviProgressionResponse getProgression(Long examenId) {
        List<Lot> lots = lotRepository.findByExamenId(examenId).stream()
                .sorted(Comparator.comparing(l -> l.getNumeroLot() != null ? l.getNumeroLot() : 0))
                .toList();

        // Charge une fois les rotations de chaque lot : la vague entière tient en mémoire
        // (K stations × K groupes), et tout le reste s'en dérive sans nouvelle requête.
        Map<Long, List<Rotation>> rotationsParLot = lots.stream()
                .collect(Collectors.toMap(Lot::getId,
                        l -> rotationRepository.findByStudentGroup_Lot_Id(l.getId())));

        // La vague « en salle » : commencée et pas finie. C'est l'invariant que garde
        // LotOuvertureService (ADR-0014-B) — il ne peut y en avoir qu'une.
        Optional<Lot> ouvert = lots.stream()
                .filter(l -> aDemarre(rotationsParLot.get(l.getId()))
                          && !estTermine(rotationsParLot.get(l.getId())))
                .findFirst();

        // Le lot que « Lot suivant » ouvrira : la première vague jamais démarrée.
        Optional<Lot> suivant = lots.stream()
                .filter(l -> !aDemarre(rotationsParLot.get(l.getId())))
                .findFirst();

        // L'alerte « lot terminé » : une vague a été ouverte et TOUTES ses rotations sont TERMINE.
        // On la cherche parmi les lots démarrés, pas seulement le lot « ouvert » ci-dessus —
        // justement parce qu'un lot entièrement terminé n'est plus « en cours ».
        Optional<Lot> dernierTermine = lots.stream()
                .filter(l -> aDemarre(rotationsParLot.get(l.getId()))
                          && estTermine(rotationsParLot.get(l.getId())))
                .reduce((a, b) -> b);

        Lot                lotAffiche = ouvert.orElse(dernierTermine.orElse(null));
        List<Rotation>     rotations  = lotAffiche != null
                ? rotationsParLot.get(lotAffiche.getId())
                : List.of();
        boolean lotTermine = ouvert.isEmpty() && dernierTermine.isPresent() && suivant.isPresent();

        return SuiviProgressionResponse.builder()
                .examenId(examenId)
                .lotOuvert(lotAffiche != null ? construireLotEnCours(lotAffiche, rotations) : null)
                .lotTermine(lotTermine)
                .lotSuivant(suivant.map(l -> SuiviProgressionResponse.LotSuivant.builder()
                        .id(l.getId())
                        .numeroLot(l.getNumeroLot())
                        .rotationsGenerees(!rotationsParLot.get(l.getId()).isEmpty())
                        .build()).orElse(null))
                .stations(construireStations(rotations))
                .build();
    }

    // =========================================================================

    private SuiviProgressionResponse.LotEnCours construireLotEnCours(Lot lot, List<Rotation> rotations) {
        Map<Integer, List<Rotation>> parGroupe = rotations.stream()
                .filter(r -> r.getStudentGroup() != null)
                .collect(Collectors.groupingBy(r -> r.getStudentGroup().getNumeroGroupe()));

        // Un groupe n'est « fini » que lorsqu'il a bouclé TOUTES les stations : il tourne encore
        // tant qu'une seule station ne l'a pas validé.
        long termines = parGroupe.values().stream()
                .filter(rs -> rs.stream().allMatch(r -> r.getStatut() == RotationStatus.TERMINE))
                .count();

        return SuiviProgressionResponse.LotEnCours.builder()
                .id(lot.getId())
                .numeroLot(lot.getNumeroLot())
                .ouvertA(lot.getOuvertA())
                .groupesTermines((int) termines)
                .groupesTotal(parGroupe.size())
                .build();
    }

    private List<SuiviProgressionResponse.StationProgression> construireStations(List<Rotation> rotations) {
        Map<Long, List<Rotation>> parStation = rotations.stream()
                .filter(r -> r.getStationId() != null)
                .collect(Collectors.groupingBy(Rotation::getStationId));

        return parStation.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> construireStation(e.getKey(), e.getValue()))
                .toList();
    }

    private SuiviProgressionResponse.StationProgression construireStation(Long stationId,
                                                                         List<Rotation> rotations) {
        List<Rotation> ordonnees = rotations.stream()
                .sorted(Comparator.comparing(r -> r.getOrdrePassage() != null ? r.getOrdrePassage() : 0))
                .toList();

        Optional<Rotation> enCours = ordonnees.stream()
                .filter(r -> r.getStatut() == RotationStatus.EN_COURS)
                .findFirst();

        long termines = ordonnees.stream().filter(r -> r.getStatut() == RotationStatus.TERMINE).count();

        int total = 0;
        int notes = 0;
        for (Rotation r : ordonnees) {
            List<RotationAssignment> assignments = assignmentRepository.findByRotationId(r.getId());
            total += assignments.size();
            for (RotationAssignment a : assignments) {
                // « Noté » = une notation existe pour ce passage. On ne conditionne PAS au verrou :
                // le responsable veut voir l'avancement en temps réel, pas seulement le définitif.
                if (notationRepository.findByAssignmentId(a.getId()).isPresent()) {
                    notes++;
                }
            }
        }

        return SuiviProgressionResponse.StationProgression.builder()
                .stationId(stationId)
                .evaluateurId(ordonnees.isEmpty() ? null : ordonnees.get(0).getEvaluateurId())
                .groupeEnCours(enCours
                        .map(r -> r.getStudentGroup() != null
                                ? r.getStudentGroup().getNumeroGroupe() : null)
                        .orElse(null))
                .rangEnCours(enCours.map(Rotation::getOrdrePassage).orElse(null))
                .etudiantsNotes(notes)
                .etudiantsTotal(total)
                .groupesTermines((int) termines)
                .groupesTotal(ordonnees.size())
                .statut(statutStation(ordonnees))
                .build();
    }

    /**
     * Statut d'une station, <b>lu</b> et jamais calculé : TERMINE si tous ses passages le sont,
     * EN_COURS si l'un est en cours, EN_ATTENTE sinon. Il n'existe pas d'état « dépassement » —
     * ce n'était pas un état mais une opinion de l'horloge sur un travail qu'elle ne voyait pas.
     */
    private String statutStation(List<Rotation> rotations) {
        if (rotations.isEmpty()) return RotationStatus.EN_ATTENTE.name();
        if (rotations.stream().allMatch(r -> r.getStatut() == RotationStatus.TERMINE)) {
            return RotationStatus.TERMINE.name();
        }
        if (rotations.stream().anyMatch(r -> r.getStatut() == RotationStatus.EN_COURS)) {
            return RotationStatus.EN_COURS.name();
        }
        return RotationStatus.EN_ATTENTE.name();
    }

    private boolean aDemarre(List<Rotation> rotations) {
        return rotations != null
                && rotations.stream().anyMatch(r -> r.getStatut() != RotationStatus.EN_ATTENTE);
    }

    private boolean estTermine(List<Rotation> rotations) {
        return rotations != null && !rotations.isEmpty()
                && rotations.stream().allMatch(r -> r.getStatut() == RotationStatus.TERMINE);
    }
}
