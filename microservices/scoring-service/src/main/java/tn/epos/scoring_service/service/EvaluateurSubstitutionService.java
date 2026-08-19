package tn.epos.scoring_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.dto.SubstitutionResult;
import tn.epos.scoring_service.entities.EvaluateurSubstitution;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationStatus;
import tn.epos.scoring_service.repositories.IEvaluateurSubstitutionRepository;
import tn.epos.scoring_service.repositories.ILotRepository;
import tn.epos.scoring_service.repositories.IRotationRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
 *
 * <p><b>La passation est COMPLÈTE (#347).</b> Le découplage valider/avancer (#209)
 * crée un seam : un partant qui a validé son dernier groupe sans cliquer
 * « Groupe suivant » laisse la station sans aucune rotation {@code EN_COURS}, et
 * le seul acte qui ouvre le rang suivant ({@code avancerGroupe}) reste gardé par
 * la propriété de sa rotation TERMINE — le remplaçant serait enfermé dehors.
 * L'acte de suppléance ouvre donc lui-même le rang transféré le plus bas quand
 * aucun groupe n'est ouvert ET que la vague a démarré à cette station (au moins
 * une rotation {@code TERMINE}). Jamais quand un groupe est {@code EN_COURS} :
 * un évaluateur au travail n'est pas bousculé, le rythme intra-lot reste
 * évaluateur-seul (#248) — ici il n'y a personne au travail, c'est tout le
 * problème. Et jamais sur un lot généré mais pas encore ouvert : ouvrir une
 * vague reste l'acte exclusif de {@code LotOuvertureService} (ADR-0014-B).
 */
@Slf4j
@Service
public class EvaluateurSubstitutionService {

    private final IRotationRepository rotationRepository;
    private final IEvaluateurSubstitutionRepository substitutionRepository;
    private final Clock clock;
    /**
     * #274 — remplacer un examinateur en pleine épreuve se borne à SA matière.
     *
     * <p>Le lot est chargé pour ça, et pas déduit des rotations : une garde d'autorisation ne
     * doit pas dépendre de l'existence de données métier. Un lot sans rotation générée doit
     * répondre « hors périmètre » à un étranger, et « la vague n'a pas démarré » au titulaire —
     * dans cet ordre.
     */
    private final ILotRepository lotRepository;
    private final MatiereAccessGuard matiereAccessGuard;

    public EvaluateurSubstitutionService(IRotationRepository rotationRepository,
                                         IEvaluateurSubstitutionRepository substitutionRepository,
                                         Clock clock,
                                         ILotRepository lotRepository,
                                         MatiereAccessGuard matiereAccessGuard) {
        this.rotationRepository = rotationRepository;
        this.substitutionRepository = substitutionRepository;
        this.clock = clock;
        this.lotRepository = lotRepository;
        this.matiereAccessGuard = matiereAccessGuard;
    }

    @Transactional
    public SubstitutionResult remplacer(Long lotId, Long stationId, Long nouvelEvaluateurId,
                                        String motif, Long decideParId) {
        matiereAccessGuard.checkExamenAccess(
                lotRepository.findById(lotId).map(Lot::getExamenId).orElse(null));

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

        // #347 — le seam « validé sans avancer » (#209) : plus aucun groupe ouvert sur la
        // station, et le droit d'avancer (avancerGroupe) est resté au partant avec sa
        // rotation TERMINE. On ouvre le rang transféré le plus bas — miroir exact de
        // LotOuvertureService.ouvrirRangInitial : le STATUT seul, jamais debutReel, qui
        // s'ancre au premier accès du remplaçant (#209, marquerDebutReel).
        //
        // Deux verrous, tous les deux nécessaires :
        //   - « aucun EN_COURS » : un évaluateur au travail n'est jamais bousculé (#248) ;
        //   - « au moins un TERMINE » (conservees > 0) : la preuve que la vague a démarré à
        //     cette station. Sans elle, remplacer sur un lot GÉNÉRÉ mais pas encore ouvert
        //     (tout EN_ATTENTE) ouvrirait une station en avance sur sa vague, et le garde
        //     d'idempotence d'ouvrirLot (aDemarre) refuserait ensuite d'ouvrir le lot —
        //     on troquerait un deadlock contre un autre.
        Rotation ouverte = null;
        boolean aucunGroupeOuvert = deLaStation.stream()
                .noneMatch(r -> r.getStatut() == RotationStatus.EN_COURS);
        if (conservees > 0 && aucunGroupeOuvert) {
            ouverte = aTransferer.stream()
                    .filter(r -> r.getStatut() == RotationStatus.EN_ATTENTE)
                    .min(Comparator.comparing(Rotation::getOrdrePassage,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
            if (ouverte != null) {
                ouverte.setStatut(RotationStatus.EN_COURS);
                rotationRepository.save(ouverte);
                log.info("Suppléance lot {} station {} : rotation {} (rang {}) OUVERTE pour le "
                                + "remplaçant — le partant avait validé son dernier groupe sans avancer (#347).",
                        lotId, stationId, ouverte.getId(), ouverte.getOrdrePassage());
            }
        }

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

        String bilan = aTransferer.size() + " groupe(s) transféré(s). " + conservees
                + " groupe(s) déjà terminé(s) restent au nom de l'évaluateur précédent.";
        if (ouverte != null) {
            bilan += " Le groupe de rang " + ouverte.getOrdrePassage()
                    + " a été ouvert pour le remplaçant : le partant avait validé son dernier "
                    + "groupe sans passer au suivant.";
        }
        return new SubstitutionResult(lotId, stationId, ancien, nouvelEvaluateurId,
                aTransferer.size(), conservees, bilan);
    }
}
