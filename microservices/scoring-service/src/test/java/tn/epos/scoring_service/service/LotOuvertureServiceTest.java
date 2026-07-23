package tn.epos.scoring_service.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.entities.LotStatus;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationStatus;
import tn.epos.scoring_service.repositories.ILotRepository;
import tn.epos.scoring_service.repositories.IRotationRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-0014-B — ouverture d'une vague par le responsable, et non-ouverture automatique
 * des vagues suivantes.
 *
 * <p>Le fil conducteur de toute la classe : <b>aucune assertion ne parle d'horloge</b>.
 * « Peut-on ouvrir la vague suivante ? » se répond exclusivement par l'état stocké des
 * rotations — tous les évaluateurs ont-ils validé leur dernier groupe.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LotOuvertureService — ADR-0014-B (avancement lot→lot, propriété responsable)")
class LotOuvertureServiceTest {

    private static final Long EXAM_ID = 7L;
    private static final Long LOT_1   = 31L;
    private static final Long LOT_2   = 32L;

    /** #252 — horloge FIXE : l'horodatage d'ouverture doit être vérifiable à la valeur près. */
    private static final LocalDateTime T0 = LocalDateTime.of(2026, Month.JULY, 22, 9, 30);

    @Mock private ILotRepository        lotRepository;
    @Mock private IRotationRepository   rotationRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;

    @Spy private Clock clock = Clock.fixed(
            T0.atZone(ZoneId.of("Africa/Tunis")).toInstant(), ZoneId.of("Africa/Tunis"));

    @InjectMocks private LotOuvertureService service;

    /** Le Lot réellement sauvegardé par l'ouverture (porteur de {@code ouvertA}). */
    private Lot lotSauvegarde() {
        ArgumentCaptor<Lot> captor = ArgumentCaptor.forClass(Lot.class);
        verify(lotRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- fixtures

    private Lot lot(Long id, int numero, LotStatus statut) {
        Lot l = new Lot();
        l.setId(id);
        l.setExamenId(EXAM_ID);
        l.setNumeroLot(numero);
        l.setStatut(statut);
        return l;
    }

    /** Rang 1 d'un lot à 3 stations : le carré latin y place un groupe par station. */
    private List<Rotation> rangUn() {
        return List.of(rotation(101L, 1, 5L), rotation(102L, 1, 6L), rotation(103L, 1, 7L));
    }

    private Rotation rotation(Long id, int rang, Long stationId) {
        Rotation r = new Rotation();
        r.setId(id);
        r.setOrdrePassage(rang);
        r.setStationId(stationId);
        r.setStatut(RotationStatus.EN_ATTENTE);
        return r;
    }

    /** Aucune rotation sortie d'EN_ATTENTE ⇒ le lot n'a jamais démarré. */
    private void lotJamaisDemarre(Long lotId) {
        lenient().when(rotationRepository.countByStudentGroup_Lot_IdAndStatutNot(
                lotId, RotationStatus.EN_ATTENTE)).thenReturn(0L);
    }

    /** Le lot est commencé, et il lui reste {@code restantes} groupes à valider. */
    private void lotEnCours(Long lotId, long restantes) {
        lenient().when(rotationRepository.countByStudentGroup_Lot_IdAndStatutNot(
                lotId, RotationStatus.EN_ATTENTE)).thenReturn(3L);
        lenient().when(rotationRepository.countByStudentGroup_Lot_IdAndStatutNot(
                lotId, RotationStatus.TERMINE)).thenReturn(restantes);
    }

    /** Toutes les rotations du lot sont TERMINE : tous les évaluateurs ont fini. */
    private void lotTermine(Long lotId) {
        lenient().when(rotationRepository.countByStudentGroup_Lot_IdAndStatutNot(
                lotId, RotationStatus.EN_ATTENTE)).thenReturn(9L);
        lenient().when(rotationRepository.countByStudentGroup_Lot_IdAndStatutNot(
                lotId, RotationStatus.TERMINE)).thenReturn(0L);
    }

    private List<Rotation> rotationsSauvegardees() {
        ArgumentCaptor<Rotation> captor = ArgumentCaptor.forClass(Rotation.class);
        verify(rotationRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    // =========================================================================
    @Nested
    @DisplayName("Première vague — s'ouvre seule à la génération")
    class PremiereVague {

        @Test
        @DisplayName("aucune autre vague lancée : le rang 1 s'ouvre, UNE rotation par station")
        void ouvre_siPremiereVagueDeLExamen() {
            Lot lot = lot(LOT_1, 1, LotStatus.EN_COURS);
            when(lotRepository.findByExamenId(EXAM_ID)).thenReturn(List.of(lot));
            when(rotationRepository.findByStudentGroup_Lot_IdAndOrdrePassage(LOT_1, 1))
                    .thenReturn(rangUn());

            boolean ouverte = service.ouvrirSiPremiereVague(lot);

            assertThat(ouverte).isTrue();
            List<Rotation> saved = rotationsSauvegardees();
            assertThat(saved).hasSize(3)
                    .allMatch(r -> r.getStatut() == RotationStatus.EN_COURS)
                    .allMatch(r -> r.getOrdrePassage() == 1);
            // Un circuit OSCE tourne en parallèle : une station distincte par rotation ouverte.
            assertThat(saved.stream().map(Rotation::getStationId).distinct()).hasSize(3);
        }

        /**
         * <b>LE BUG (session 23, reproduit sur l'examen 31).</b> Générer le lot 2 pendant que
         * le lot 1 tournait ouvrait une seconde vague : la station 54 affichait les rotations
         * 173 (lot 32) ET 177 (lot 33) EN_COURS en même temps, même évaluateur. Un évaluateur
         * ne peut recevoir qu'un groupe à la fois — le tableau mentait.
         */
        @Test
        @DisplayName("BUG #207 : générer le lot 2 pendant que le lot 1 tourne n'ouvre RIEN")
        void nOuvrePas_siUneAutreVagueEstDejaLancee() {
            Lot lot2 = lot(LOT_2, 2, LotStatus.EN_COURS);
            when(lotRepository.findByExamenId(EXAM_ID))
                    .thenReturn(List.of(lot(LOT_1, 1, LotStatus.EN_COURS), lot2));
            lotEnCours(LOT_1, 4L);

            boolean ouverte = service.ouvrirSiPremiereVague(lot2);

            assertThat(ouverte).isFalse();
            verify(rotationRepository, never()).save(any(Rotation.class));
        }

        @Test
        @DisplayName("une vague DÉJÀ TERMINÉE ne rouvre rien non plus : c'est au responsable")
        void nOuvrePas_siUneVaguePrecedenteEstTerminee() {
            Lot lot2 = lot(LOT_2, 2, LotStatus.EN_COURS);
            when(lotRepository.findByExamenId(EXAM_ID))
                    .thenReturn(List.of(lot(LOT_1, 1, LotStatus.EN_COURS), lot2));
            lotTermine(LOT_1);

            assertThat(service.ouvrirSiPremiereVague(lot2)).isFalse();
            verify(rotationRepository, never()).save(any(Rotation.class));
        }

        /**
         * La condition porte sur l'ÉTAT, pas sur {@code numeroLot} : régénérer le lot 1 après
         * une erreur (ses rotations sont purgées, aucune autre vague n'a bougé) doit rouvrir.
         */
        @Test
        @DisplayName("condition sur l'ÉTAT et non sur le numéro : un lot 2 seul à avoir bougé s'ouvre")
        void ouvre_memeSiCeNestPasLeLotNumeroUn() {
            Lot lot2 = lot(LOT_2, 2, LotStatus.EN_COURS);
            when(lotRepository.findByExamenId(EXAM_ID))
                    .thenReturn(List.of(lot(LOT_1, 1, LotStatus.EN_COURS), lot2));
            lotJamaisDemarre(LOT_1);
            when(rotationRepository.findByStudentGroup_Lot_IdAndOrdrePassage(LOT_2, 1))
                    .thenReturn(rangUn());

            assertThat(service.ouvrirSiPremiereVague(lot2)).isTrue();
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("« Lot suivant » — la poignée de main du responsable")
    class LotSuivant {

        private void lotOuvrable(Lot lot) {
            when(lotRepository.findById(lot.getId())).thenReturn(Optional.of(lot));
            lenient().when(rotationRepository.countByStudentGroupLotId(lot.getId())).thenReturn(9L);
        }

        @Test
        @DisplayName("la vague précédente terminée : le rang 1 du lot suivant s'ouvre")
        void ouvre_quandTousLesEvaluateursOntFini() {
            Lot lot2 = lot(LOT_2, 2, LotStatus.EN_COURS);
            lotOuvrable(lot2);
            lotJamaisDemarre(LOT_2);
            when(lotRepository.findByExamenId(EXAM_ID))
                    .thenReturn(List.of(lot(LOT_1, 1, LotStatus.EN_COURS), lot2));
            lotTermine(LOT_1);
            when(rotationRepository.findByStudentGroup_Lot_IdAndOrdrePassage(LOT_2, 1))
                    .thenReturn(rangUn());

            service.ouvrirLot(LOT_2);

            assertThat(rotationsSauvegardees())
                    .hasSize(3)
                    .allMatch(r -> r.getStatut() == RotationStatus.EN_COURS);
        }

        /**
         * LA garde qui justifie l'action : un lot est une vague servie par TOUS les
         * évaluateurs. Tant qu'un seul n'a pas validé son dernier groupe, ouvrir la vague
         * suivante enverrait des étudiants sur une station encore occupée.
         */
        @Test
        @DisplayName("refuse BRUYAMMENT tant qu'un évaluateur n'a pas validé son dernier groupe")
        void refuse_siVaguePrecedenteInachevee() {
            Lot lot2 = lot(LOT_2, 2, LotStatus.EN_COURS);
            lotOuvrable(lot2);
            lotJamaisDemarre(LOT_2);
            when(lotRepository.findByExamenId(EXAM_ID))
                    .thenReturn(List.of(lot(LOT_1, 1, LotStatus.EN_COURS), lot2));
            lotEnCours(LOT_1, 2L);

            assertThatThrownBy(() -> service.ouvrirLot(LOT_2))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Lot 1")
                    .hasMessageContaining("2 groupe(s)");

            verify(rotationRepository, never()).save(any(Rotation.class));
        }

        /**
         * <b>Le point que Nada a explicitement posé</b> : la fin d'un lot ne se décide pas à
         * l'horloge. Ici la vague précédente est planifiée sur des créneaux largement passés
         * (hier), mais deux groupes restent à valider — l'ouverture doit être refusée quand
         * même. Aucune durée, aucun {@code now} n'entre dans la décision (ADR-0014 : plancher
         * jamais plafond).
         */
        @Test
        @DisplayName("le temps écoulé ne débloque RIEN : créneaux passés mais groupes non validés ⇒ refus")
        void refuse_memeSiLesCreneauxSontLargementPasses() {
            Lot lot1 = lot(LOT_1, 1, LotStatus.EN_COURS);
            Lot lot2 = lot(LOT_2, 2, LotStatus.EN_COURS);
            lotOuvrable(lot2);
            lotJamaisDemarre(LOT_2);
            when(lotRepository.findByExamenId(EXAM_ID)).thenReturn(List.of(lot1, lot2));
            lotEnCours(LOT_1, 1L);

            // Créneaux d'hier : sous l'ancien modèle « ceilé », ce lot se serait auto-retiré.
            Rotation vieille = rotation(199L, 1, 5L);
            vieille.setDebutCreneau(LocalDateTime.now().minusDays(1));
            lenient().when(rotationRepository.findByStudentGroup_Lot_IdAndOrdrePassage(LOT_1, 1))
                    .thenReturn(List.of(vieille));

            assertThatThrownBy(() -> service.ouvrirLot(LOT_2))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("toujours en cours");
        }

        @Test
        @DisplayName("refuse si la présence n'a pas été enregistrée")
        void refuse_siPresenceNonPrise() {
            Lot lot2 = lot(LOT_2, 2, LotStatus.EN_ATTENTE); // EN_ATTENTE = présence non prise
            when(lotRepository.findById(LOT_2)).thenReturn(Optional.of(lot2));

            assertThatThrownBy(() -> service.ouvrirLot(LOT_2))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("présence");
        }

        @Test
        @DisplayName("refuse si le planning du lot n'a pas encore été généré")
        void refuse_siAucuneRotation() {
            Lot lot2 = lot(LOT_2, 2, LotStatus.EN_COURS);
            when(lotRepository.findById(LOT_2)).thenReturn(Optional.of(lot2));
            when(rotationRepository.countByStudentGroupLotId(LOT_2)).thenReturn(0L);

            assertThatThrownBy(() -> service.ouvrirLot(LOT_2))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("aucune rotation générée");
        }

        /**
         * Ré-ouvrir doit échouer, pas passer en silence : le rang 1 pourrait avoir été validé
         * puis le rang 2 ouvert — une ré-ouverture ferait réapparaître un groupe déjà noté.
         */
        @Test
        @DisplayName("refuse si la vague est déjà ouverte (jamais un no-op silencieux)")
        void refuse_siDejaOuverte() {
            Lot lot2 = lot(LOT_2, 2, LotStatus.EN_COURS);
            when(lotRepository.findById(LOT_2)).thenReturn(Optional.of(lot2));
            when(rotationRepository.countByStudentGroupLotId(LOT_2)).thenReturn(9L);
            lotEnCours(LOT_2, 5L);

            assertThatThrownBy(() -> service.ouvrirLot(LOT_2))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("déjà ouverte");

            verify(rotationRepository, never()).save(any(Rotation.class));
        }

        @Test
        @DisplayName("404 sur un lot inconnu")
        void refuse_siLotInconnu() {
            when(lotRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.ouvrirLot(999L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        /**
         * {@code Lot.statut} veut déjà dire « présence prise » (LotAssignmentService:229) :
         * l'ouverture ne doit donc PAS y toucher, sous peine de surcharger un champ déjà
         * ambigu. L'état « ouvert » se lit sur les rotations, et nulle part ailleurs.
         *
         * <p>⚠️ L'assertion a changé de MÉCANISME avec #252, pas d'intention : le lot est
         * désormais bien sauvegardé — pour y poser {@code ouvertA} — donc « ne sauvegarde jamais
         * le Lot » ne dit plus ce qu'on veut dire. On vérifie ce qui compte vraiment : le statut
         * ressort inchangé.
         */
        @Test
        @DisplayName("ne touche pas Lot.statut — le champ signifie déjà « présence prise »")
        void nEcritPas_leStatutDuLot() {
            Lot lot2 = lot(LOT_2, 2, LotStatus.EN_COURS);
            lotOuvrable(lot2);
            lotJamaisDemarre(LOT_2);
            when(lotRepository.findByExamenId(EXAM_ID)).thenReturn(List.of(lot2));
            when(rotationRepository.findByStudentGroup_Lot_IdAndOrdrePassage(LOT_2, 1))
                    .thenReturn(rangUn());

            service.ouvrirLot(LOT_2);

            assertThat(lotSauvegarde().getStatut()).isEqualTo(LotStatus.EN_COURS);
        }

        /**
         * #252 — l'ouverture date la vague. C'est la seule ancre honnête du chronomètre de Suivi :
         * {@code launched_at} ne connaît que la première vague, et {@code debutCreneau} est un
         * horaire PRÉVU à la génération, pas le moment où la vague a commencé.
         */
        @Test
        @DisplayName("#252 : l'ouverture horodate la vague (ouvertA)")
        void horodate_lOuvertureDeLaVague() {
            Lot lot2 = lot(LOT_2, 2, LotStatus.EN_COURS);
            lotOuvrable(lot2);
            lotJamaisDemarre(LOT_2);
            when(lotRepository.findByExamenId(EXAM_ID)).thenReturn(List.of(lot2));
            when(rotationRepository.findByStudentGroup_Lot_IdAndOrdrePassage(LOT_2, 1))
                    .thenReturn(rangUn());

            assertThat(lot2.getOuvertA()).isNull();

            service.ouvrirLot(LOT_2);

            assertThat(lotSauvegarde().getOuvertA()).isEqualTo(T0);
        }

        @Test
        @DisplayName("diffuse un rafraîchissement sur le topic du lot")
        void diffuse_surLeTopicDuLot() {
            Lot lot2 = lot(LOT_2, 2, LotStatus.EN_COURS);
            lotOuvrable(lot2);
            lotJamaisDemarre(LOT_2);
            when(lotRepository.findByExamenId(EXAM_ID)).thenReturn(List.of(lot2));
            when(rotationRepository.findByStudentGroup_Lot_IdAndOrdrePassage(LOT_2, 1))
                    .thenReturn(rangUn());

            service.ouvrirLot(LOT_2);

            verify(messagingTemplate).convertAndSend(eq("/topic/lots/32/status"), any(Object.class));
        }
    }
}
