package tn.epos.scoring_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.dto.SubstitutionResult;
import tn.epos.scoring_service.entities.EvaluateurSubstitution;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationStatus;
import tn.epos.scoring_service.repositories.IEvaluateurSubstitutionRepository;
import tn.epos.scoring_service.repositories.IRotationRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ADR-0017 — remplacer l'évaluateur d'une station en pleine épreuve.
 *
 * <p><b>Un seul cas réel.</b> Malade la veille ou le matin ? Il n'y a rien à
 * réparer : l'évaluateur n'est lié à rien tant que la vague n'a pas démarré, la
 * génération lit l'affectation COURANTE de la station
 * ({@code RotationGenerationService:239-240}). On réaffecte, puis on démarre.
 * Ce service n'existe que pour le départ EN COURS d'examen, quand les rotations
 * existent déjà et figent un évaluateur qui n'est plus là.
 *
 * <p><b>Acte du responsable, jamais de l'évaluateur</b> (ADR-0017 §1, même
 * argument qu'ADR-0014-B) : une station sert une vague qui appartient à l'examen
 * entier, donc qui la tient n'est pas la décision de celui qui la tient.
 *
 * <p><b>On ne réécrit pas l'histoire.</b> Seules les rotations non terminées
 * changent de main ; celles déjà {@code TERMINE} gardent leur évaluateur, et les
 * notes déjà saisies gardent leur {@code saisi_par} (V15, #213). C'est
 * précisément pour ça que la traçabilité a été livrée avant la suppléance :
 * l'inverse aurait repeint le travail du partant au nom du remplaçant.
 */
@Slf4j
@Service
public class EvaluateurSubstitutionService {

    private final IRotationRepository rotationRepository;
    private final IEvaluateurSubstitutionRepository substitutionRepository;
    private final Clock clock;

    public EvaluateurSubstitutionService(IRotationRepository rotationRepository,
                                         IEvaluateurSubstitutionRepository substitutionRepository,
                                         Clock clock) {
        this.rotationRepository = rotationRepository;
        this.substitutionRepository = substitutionRepository;
        this.clock = clock;
    }

    @Transactional
    public SubstitutionResult remplacer(Long lotId, Long stationId, Long nouvelEvaluateurId,
                                        String motif, Long decideParId) {
        List<Rotation> duLot = rotationRepository.findByStudentGroup_Lot_Id(lotId);

        List<Rotation> deLaStation = duLot.stream()
                .filter(r -> stationId.equals(r.getStationId()))
                .toList();
        if (deLaStation.isEmpty()) {
            throw new ResourceNotFoundException(
                    "Aucun groupe pour la station " + stationId + " dans le lot " + lotId
                            + " : la vague n'a pas encore démarré. Réaffectez la station et "
                            + "démarrez le lot — aucune suppléance n'est nécessaire (ADR-0017 §2).");
        }

        Long ancien = deLaStation.get(0).getEvaluateurId();
        if (nouvelEvaluateurId.equals(ancien)) {
            throw new BusinessException(
                    "L'évaluateur " + nouvelEvaluateurId + " tient déjà cette station.");
        }

        // Une personne ne peut pas tenir deux stations de la MÊME vague en même
        // temps : elles tournent simultanément. C'est la version locale et sûre
        // de la règle #265 ; le contrôle inter-examens vit dans l'exam-service et
        // reste à brancher (voir la PR).
        boolean dejaSurUneAutreStation = duLot.stream()
                .anyMatch(r -> nouvelEvaluateurId.equals(r.getEvaluateurId())
                        && !stationId.equals(r.getStationId())
                        && r.getStatut() != RotationStatus.TERMINE);
        if (dejaSurUneAutreStation) {
            throw new BusinessException(
                    "L'évaluateur " + nouvelEvaluateurId + " tient déjà une autre station de ce lot "
                            + "et ne peut pas en servir deux à la fois. Choisissez quelqu'un d'autre.");
        }

        List<Rotation> aTransferer = new ArrayList<>();
        int conservees = 0;
        for (Rotation r : deLaStation) {
            if (r.getStatut() == RotationStatus.TERMINE) {
                conservees++;   // travail fini : il reste au nom de celui qui l'a fait
            } else {
                r.setEvaluateurId(nouvelEvaluateurId);
                aTransferer.add(r);
            }
        }

        if (aTransferer.isEmpty()) {
            throw new BusinessException(
                    "Tous les groupes de cette station sont déjà terminés : il n'y a rien à "
                            + "reprendre, et le travail fait reste au nom de son auteur.");
        }
        rotationRepository.saveAll(aTransferer);

        EvaluateurSubstitution trace = new EvaluateurSubstitution();
        trace.setLotId(lotId);
        trace.setStationId(stationId);
        trace.setAncienEvaluateur(ancien);
        trace.setNouvelEvaluateur(nouvelEvaluateurId);
        trace.setRotationsTransferees(aTransferer.size());
        trace.setMotif(motif);
        trace.setDecidePar(decideParId);
        trace.setSurvenuA(LocalDateTime.now(clock));
        substitutionRepository.save(trace);

        log.info("Suppléance lot {} station {} : {} -> {} ({} groupe(s) transféré(s), "
                        + "{} conservé(s)), décidée par {} — motif : {}",
                lotId, stationId, ancien, nouvelEvaluateurId,
                aTransferer.size(), conservees, decideParId, motif);

        return new SubstitutionResult(lotId, stationId, ancien, nouvelEvaluateurId,
                aTransferer.size(), conservees,
                aTransferer.size() + " groupe(s) transféré(s). " + conservees
                        + " groupe(s) déjà terminé(s) restent au nom de l'évaluateur précédent.");
    }
}
