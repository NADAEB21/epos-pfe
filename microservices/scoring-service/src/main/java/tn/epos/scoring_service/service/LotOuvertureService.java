package tn.epos.scoring_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.dto.websocket.LotStatusMessage;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.entities.LotStatus;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationStatus;
import tn.epos.scoring_service.repositories.ILotRepository;
import tn.epos.scoring_service.repositories.IRotationRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * ADR-0014-B — <b>ouverture d'un lot (= d'une vague d'étudiants)</b>, seule porte
 * d'écriture de {@code RotationStatus.EN_COURS} au niveau LOT.
 *
 * <p><b>Le modèle.</b> Un lot est une <i>vague</i> : tous les évaluateurs servent le même
 * lot en même temps, chacun à sa station. Il y a donc deux avancements distincts, avec
 * deux propriétaires :
 * <ul>
 *   <li><b>groupe → groupe</b>, à l'intérieur d'un lot : c'est l'<b>évaluateur</b>, station
 *       par station (« Groupe suivant », {@code EvaluateurDashboardService.validerGroupe}).</li>
 *   <li><b>lot → lot</b> : c'est le <b>responsable</b>, une seule fois pour tout le circuit.
 *       Ouvrir une vague engage la salle entière et les stations de tous les collègues :
 *       aucun évaluateur ne peut porter cette action.</li>
 * </ul>
 *
 * <p><b>Le bug que cette classe supprime.</b> Avant, la génération d'un lot ouvrait
 * inconditionnellement son rang 1. Générer le lot N+1 pendant que le lot N tournait
 * ouvrait donc une <i>seconde</i> vague concurrente : la même station affichait deux
 * groupes {@code EN_COURS} à la fois, ce qui est impossible dans la salle. Le défaut
 * n'était pas « les lots s'ouvrent tout seuls » mais « les lots <b>autres que la vague
 * active</b> s'ouvrent tout seuls » : le lot 1, lui, est déjà gardé par deux actes humains
 * au-dessus (examen lancé, présence enregistrée) et doit continuer à s'ouvrir seul.
 *
 * <p><b>Aucune horloge, nulle part.</b> « Peut-on passer à la vague suivante ? » se répond
 * uniquement par l'état stocké des rotations — tous les évaluateurs ont-ils validé leur
 * dernier groupe — jamais par une durée écoulée (ADR-0014 : l'horloge garde son rôle de
 * PLANCHER, jamais de PLAFOND).
 *
 * <p><b>Pourquoi {@code Lot.statut} n'est pas lu ici.</b> La colonne est déjà surchargée et
 * veut dire autre chose : {@code LotAssignmentService:113} crée les lots {@code EN_ATTENTE},
 * puis l'<i>enregistrement de la présence</i> les passe {@code EN_COURS}
 * ({@code LotAssignmentService:229}). Un lot est donc {@code EN_COURS} <i>avant</i> d'avoir
 * jamais été ouvert. L'état « ouvert / terminé » se lit exclusivement sur les rotations —
 * ce qui a aussi l'avantage de ne pas pouvoir être maquillé à la main via
 * {@code PUT /api/lots/{id}}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LotOuvertureService {

    /** Rang de passage de la première vague (le carré latin y place 1 groupe par station). */
    private static final int RANG_INITIAL = 1;

    private final ILotRepository        lotRepository;
    private final IRotationRepository   rotationRepository;
    private final SimpMessagingTemplate messagingTemplate;
    /** ADR-0010 — « maintenant » vient du bean, pas de l'horloge système (zone épinglée). */
    private final Clock                 clock;
    /** #274 — ouvrir une vague est un acte de conduite : il se borne à SA matière. */
    private final MatiereAccessGuard    matiereAccessGuard;

    // =========================================================================
    // ACTION RESPONSABLE — « Lot suivant »
    // =========================================================================

    /**
     * Ouvre explicitement un lot : ses rotations de rang 1 passent {@code EN_COURS}, une
     * par station. C'est l'action « Lot suivant » du responsable (#208).
     *
     * <p>Les refus sont <b>bruyants</b> et jamais silencieux : ouvrir la mauvaise vague
     * envoie une salle entière d'étudiants au mauvais endroit.
     *
     * @throws ResourceNotFoundException lot inconnu
     * @throws BusinessException         présence non enregistrée, planning non généré, lot
     *                                   déjà ouvert, ou vague précédente pas encore terminée
     */
    @Transactional
    public void ouvrirLot(Long lotId) {
        Lot lot = lotRepository.findById(lotId)
                .orElseThrow(() -> new ResourceNotFoundException("Lot introuvable : " + lotId));

        // 0. #274 — AVANT toute règle métier : la garde d'autorisation. Jusqu'ici la seule
        //    protection était `hasAnyRole('SUPER_ADMIN','RESPONSABLE_MATIERE')` sur le
        //    contrôleur — le rôle NU, sans matière. Un responsable de Toxicologie pouvait donc
        //    ouvrir une vague d'une épreuve de Chimie thérapeutique. Le refus doit précéder les
        //    messages métier : sinon on renseigne un appelant illégitime sur l'état de la salle.
        matiereAccessGuard.checkExamenAccess(lot.getExamenId());

        // 1. La présence conditionne déjà la génération côté web (lots.component.ts:220).
        //    On le redit ici pour que l'API refuse proprement au lieu de compter 0 rotation.
        if (lot.getStatut() == LotStatus.EN_ATTENTE) {
            throw new BusinessException("Lot " + numero(lot) + " : enregistrez d'abord la présence "
                    + "des étudiants de cette vague avant de l'ouvrir.");
        }

        // 2. Ouvrir suppose un planning : sans rotations il n'y a aucun rang 1 à ouvrir.
        if (rotationRepository.countByStudentGroupLotId(lotId) == 0) {
            throw new BusinessException("Lot " + numero(lot) + " : aucune rotation générée. "
                    + "Générez le planning de ce lot avant de l'ouvrir.");
        }

        // 3. Idempotence explicite plutôt que silencieuse : ré-ouvrir un lot déjà commencé
        //    ré-ouvrirait un groupe que l'évaluateur a peut-être déjà validé.
        if (aDemarre(lotId)) {
            throw new BusinessException("Lot " + numero(lot) + " : cette vague est déjà ouverte "
                    + "(ou déjà terminée). Aucune action n'est nécessaire.");
        }

        // 4. LA POIGNÉE DE MAIN (ADR-0014-B §4) — la seule vraie raison d'être de cette garde.
        //    Elle se lit sur les rotations, jamais sur une durée : la vague précédente est
        //    finie quand TOUS les évaluateurs ont validé leur dernier groupe, point.
        Optional<Lot> vagueEnCours = lotDemarreNonTermine(lot.getExamenId(), lotId);
        if (vagueEnCours.isPresent()) {
            Lot bloquant = vagueEnCours.get();
            long restantes = rotationRepository.countByStudentGroup_Lot_IdAndStatutNot(
                    bloquant.getId(), RotationStatus.TERMINE);
            throw new BusinessException("Lot " + numero(bloquant) + " toujours en cours : "
                    + restantes + " groupe(s) restent à valider. Attendez que tous les évaluateurs "
                    + "aient terminé cette vague avant d'ouvrir le lot " + numero(lot) + ".");
        }

        int ouvertes = ouvrirRangInitial(lot);
        log.info("Lot {} (n°{}) OUVERT par le responsable : {} station(s) démarrées.",
                lot.getId(), numero(lot), ouvertes);
    }

    // =========================================================================
    // PREMIÈRE VAGUE — ouverture automatique à la génération
    // =========================================================================

    /**
     * Ouvre le lot <b>si et seulement si</b> c'est la première vague de l'examen à devenir
     * gradable. Appelé par {@link RotationGenerationService} juste après la génération.
     *
     * <p>La condition porte sur l'<b>état</b> (« aucune rotation d'un AUTRE lot de cet examen
     * n'a quitté {@code EN_ATTENTE} ») et non sur {@code numeroLot}. C'est volontaire : ainsi
     * elle reste juste quand les lots sont générés dans le désordre, et quand un lot est
     * <i>régénéré</i> après une erreur ({@code wipeLotGroups}) — cas où « c'est le lot 1 »
     * serait faux ou trompeur.
     *
     * <p>Pourquoi automatique ici : « l'examen est lancé + la présence est prise » sont déjà
     * deux actes humains au-dessus de la génération, et il n'existe aucun chemin d'appel
     * exam-service → scoring (même mur qu'ADR-0015) permettant au lancement d'ouvrir le lot 1
     * lui-même. Exiger un troisième clic le jour J n'achèterait rien.
     *
     * @return {@code true} si la vague a été ouverte, {@code false} si l'examen a déjà une
     *         vague en cours ou terminée — le responsable ouvrira alors ce lot lui-même.
     */
    @Transactional
    public boolean ouvrirSiPremiereVague(Lot lot) {
        boolean autreVagueDejaLancee = lotRepository.findByExamenId(lot.getExamenId()).stream()
                .filter(autre -> !autre.getId().equals(lot.getId()))
                .anyMatch(autre -> aDemarre(autre.getId()));

        if (autreVagueDejaLancee) {
            log.info("Lot {} (n°{}) généré mais NON ouvert : l'examen {} a déjà une vague lancée. "
                            + "Le responsable l'ouvrira via « Lot suivant » (ADR-0014-B).",
                    lot.getId(), numero(lot), lot.getExamenId());
            return false;
        }

        int ouvertes = ouvrirRangInitial(lot);
        log.info("Lot {} (n°{}) : première vague de l'examen {} — {} station(s) ouvertes "
                + "automatiquement.", lot.getId(), numero(lot), lot.getExamenId(), ouvertes);
        return true;
    }

    // =========================================================================
    // MÉTHODES PRIVÉES
    // =========================================================================

    /**
     * Passe {@code EN_COURS} les rotations de rang 1 encore {@code EN_ATTENTE}. Le carré
     * latin garantit une rotation par station à ce rang : on ouvre donc exactement un
     * groupe par station, jamais deux.
     */
    private int ouvrirRangInitial(Lot lot) {
        List<Rotation> rangUn =
                rotationRepository.findByStudentGroup_Lot_IdAndOrdrePassage(lot.getId(), RANG_INITIAL);

        int ouvertes = 0;
        for (Rotation r : rangUn) {
            if (r.getStatut() == RotationStatus.EN_ATTENTE) {
                r.setStatut(RotationStatus.EN_COURS);
                rotationRepository.save(r);
                ouvertes++;
            }
        }

        // #252 — l'ancre du chronomètre de Suivi. C'est le SEUL endroit qui ouvre une vague,
        // donc le seul qui peut dater ce fait. On l'écrit ici et pas à la génération : générer
        // n'est plus ouvrir (ADR-0014-B), et un lot généré la veille au soir ne doit pas
        // compter comme s'il tournait depuis.
        // ⚠️ Cette écriture, elle, touche bien Lot : contrairement à `statut` (surchargé,
        // il signifie « présence prise »), `ouvertA` n'a qu'un seul sens et qu'un seul auteur.
        lot.setOuvertA(LocalDateTime.now(clock));
        lotRepository.save(lot);

        diffuser(lot);
        return ouvertes;
    }

    /** Une vague est « démarrée » dès qu'une de ses rotations a quitté {@code EN_ATTENTE}. */
    private boolean aDemarre(Long lotId) {
        return rotationRepository
                .countByStudentGroup_Lot_IdAndStatutNot(lotId, RotationStatus.EN_ATTENTE) > 0;
    }

    /**
     * Le lot de cet examen qui est commencé mais pas fini — c'est-à-dire la vague
     * actuellement dans la salle. Un lot terminé ne bloque rien ; un lot jamais ouvert non
     * plus. Au plus un lot peut être dans cet état, et c'est précisément l'invariant que
     * cette classe protège.
     */
    private Optional<Lot> lotDemarreNonTermine(Long examenId, Long exclureLotId) {
        return lotRepository.findByExamenId(examenId).stream()
                .filter(l -> !l.getId().equals(exclureLotId))
                .filter(l -> aDemarre(l.getId()))
                .filter(l -> rotationRepository.countByStudentGroup_Lot_IdAndStatutNot(
                        l.getId(), RotationStatus.TERMINE) > 0)
                .findFirst();
    }

    /**
     * Rafraîchit les clients abonnés au lot. Le statut diffusé reste {@code "EN_COURS"} par
     * cohérence avec {@code validerGroupe} ; côté Flutter le message ne sert qu'à déclencher
     * un rechargement du tableau de bord ({@code session_bloc.dart:124}), il n'est pas
     * interprété. Côté web il n'y a <b>aucun</b> abonné (pas de dépendance STOMP), d'où
     * l'alerte « lot terminé » dérivée du REST dans #208.
     */
    private void diffuser(Lot lot) {
        messagingTemplate.convertAndSend(
                String.format(LotStatusMessage.TOPIC, lot.getId()),
                LotStatusMessage.builder()
                        .lotId(lot.getId())
                        .examenId(lot.getExamenId())
                        .numeroLot(lot.getNumeroLot())
                        .statut(RotationStatus.EN_COURS.name())
                        .build());
    }

    private String numero(Lot lot) {
        return lot.getNumeroLot() != null ? String.valueOf(lot.getNumeroLot()) : "?";
    }
}
