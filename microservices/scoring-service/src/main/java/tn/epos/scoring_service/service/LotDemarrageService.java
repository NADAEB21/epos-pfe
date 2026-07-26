package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.scoring_service.dto.DemarrageResult;
import tn.epos.scoring_service.dto.GenerationResult;

import java.util.List;

/**
 * #185 — « Présence & démarrer » : L'ACTE UNIQUE du jour J, côté conducteur.
 *
 * <p><b>Le problème vécu (Nada, 2026-07-24)</b> : démarrer un lot exigeait trois actions sur
 * deux onglets — marquer la présence (Lots & présence), générer les rotations (retour ailleurs),
 * et l'ouverture de vague. « Même moi j'ai mis du temps à l'apprendre » — pour un enseignant de
 * pharmacie sans bagage IT, un protocole éclaté est un protocole cassé.
 *
 * <p>Ce service soude la séquence en UN acte transactionnel :
 * <ol>
 *   <li><b>présence</b> ({@code markPresence}) — qui est venu, les absents en option ;</li>
 *   <li><b>génération</b> ({@code generateForLot}) — le circuit du lot, construit sur les
 *       présents (les rotations restent un acte du jour J, décision explicite : les groupes se
 *       bâtissent sur la réalité, pas sur une liste théorique à réparer au matin) ;</li>
 *   <li><b>ouverture</b> — DÉLÉGUÉE, pas dupliquée : {@code generateForLot} appelle déjà
 *       {@code LotOuvertureService.ouvrirSiPremiereVague} (ADR-0014-B). Première vague de
 *       l'examen ⇒ elle s'ouvre ; vague suivante ⇒ elle attend le « Ouvrir le lot N » du
 *       responsable, gardé sur l'état des rotations. Ce service n'introduit AUCUN nouvel
 *       écrivain d'EN_COURS.</li>
 * </ol>
 *
 * <p>Transactionnel : si la génération refuse (garde anti-perte #188, examen pas EN_COURS…),
 * la présence est annulée avec elle — pas d'état intermédiaire « présence prise, pas de
 * circuit » à comprendre pour l'utilisateur. Les refus remontent tels quels : ils sont déjà
 * bruyants et nominatifs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LotDemarrageService {

    private final LotAssignmentService    lotAssignmentService;
    private final RotationGenerationService rotationGenerationService;

    @Transactional
    public DemarrageResult presenceEtDemarrer(Long lotId, List<Long> absents) {
        var presence = lotAssignmentService.markPresence(lotId, absents);
        GenerationResult generation = rotationGenerationService.generateForLot(lotId);
        log.info("Lot {} : présence ({} présents / {} absents) + génération ({} rotations) en un acte.",
                lotId, presence.presents(), presence.absents(), generation.rotations());
        return new DemarrageResult(lotId, presence.presents(), presence.absents(),
                generation.rotations(), generation.assignments(), generation.avertissement());
    }
}
