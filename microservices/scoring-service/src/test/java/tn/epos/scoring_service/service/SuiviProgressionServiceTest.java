package tn.epos.scoring_service.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.scoring_service.dto.dashboard.SuiviProgressionResponse;
import tn.epos.scoring_service.entities.*;
import tn.epos.scoring_service.repositories.ILotRepository;
import tn.epos.scoring_service.repositories.INotationRepository;
import tn.epos.scoring_service.repositories.IRotationAssignmentRepository;
import tn.epos.scoring_service.repositories.IRotationRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * #208 / #252 — le tableau de progression du responsable.
 *
 * <p>Fil conducteur : <b>aucune assertion ne parle d'horloge</b>, et aucune n'attend d'état
 * « dépassement ». Le tableau lit l'avancement stocké ; c'est précisément parce que le web le
 * re-déduisait de {@code now} qu'il affichait « dépassement — encore en cours » sur des rotations
 * {@code TERMINE} en base.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SuiviProgressionService — progression lue, jamais déduite de l'heure")
class SuiviProgressionServiceTest {

    private static final Long EXAM = 33L;
    private static final Long LOT1 = 40L;
    private static final Long LOT2 = 41L;

    /** Horloge FIXE : la durée écoulée doit être vérifiable à la seconde près. */
    private static final LocalDateTime MAINTENANT = LocalDateTime.of(2026, Month.JULY, 22, 10, 0);

    @Mock private ILotRepository                lotRepository;
    @Mock private IRotationRepository           rotationRepository;
    @Mock private IRotationAssignmentRepository assignmentRepository;
    @Mock private INotationRepository           notationRepository;

    @Spy private Clock clock = Clock.fixed(
            MAINTENANT.atZone(ZoneId.of("Africa/Tunis")).toInstant(), ZoneId.of("Africa/Tunis"));

    /** #274 — permissif ici : le perimetre de matiere a ses propres tests. */
    @Mock private MatiereAccessGuard matiereAccessGuard;

    @InjectMocks private SuiviProgressionService service;

    // ---------------------------------------------------------------- fixtures

    private Lot lot(Long id, int numero, LocalDateTime ouvertA) {
        Lot l = new Lot();
        l.setId(id);
        l.setExamenId(EXAM);
        l.setNumeroLot(numero);
        l.setStatut(LotStatus.EN_COURS);   // = « présence prise », PAS « vague ouverte »
        l.setOuvertA(ouvertA);
        return l;
    }

    private Rotation rot(Long id, Long stationId, int rang, int groupe, RotationStatus statut) {
        Rotation r = new Rotation();
        r.setId(id);
        r.setStationId(stationId);
        r.setEvaluateurId(stationId);       // 1 station = 1 évaluateur sur le fixture
        r.setOrdrePassage(rang);
        r.setStatut(statut);
        StudentGroup sg = new StudentGroup();
        sg.setNumeroGroupe(groupe);
        r.setStudentGroup(sg);
        return r;
    }

    private void lient(Lot l, List<Rotation> rotations) {
        for (Rotation r : rotations) {
            if (r.getStudentGroup() != null) r.getStudentGroup().setLot(l);
        }
        lenient().when(rotationRepository.findByStudentGroup_Lot_Id(l.getId())).thenReturn(rotations);
    }

    /** Chaque rotation porte {@code nb} étudiants, dont {@code notes} déjà notés. */
    private void assignments(Long rotationId, int nb, int notes) {
        List<RotationAssignment> list = new java.util.ArrayList<>();
        for (int i = 0; i < nb; i++) {
            RotationAssignment a = new RotationAssignment();
            a.setId(rotationId * 100 + i);
            list.add(a);
            lenient().when(notationRepository.findByAssignmentId(a.getId()))
                    .thenReturn(i < notes ? Optional.of(new Notation()) : Optional.empty());
        }
        lenient().when(assignmentRepository.findByRotationId(rotationId)).thenReturn(list);
    }

    // =========================================================================
    @Nested
    @DisplayName("Vague en cours")
    class VagueEnCours {

        @Test
        @DisplayName("expose le groupe en cours et « N/M notés » par station, sans aucun dépassement")
        void progression_parStation() {
            LocalDateTime ouvert = LocalDateTime.of(2026, Month.JULY, 22, 9, 0);
            Lot l1 = lot(LOT1, 1, ouvert);
            // Station 58 : rang 1 (groupe 1) TERMINE, rang 2 (groupe 2) EN_COURS
            // Station 59 : rang 1 (groupe 2) EN_COURS  ← carré latin : groupe 2 d'abord
            List<Rotation> rotations = List.of(
                    rot(201L, 58L, 1, 1, RotationStatus.TERMINE),
                    rot(204L, 58L, 2, 2, RotationStatus.EN_COURS),
                    rot(202L, 59L, 1, 2, RotationStatus.EN_COURS),
                    rot(203L, 59L, 2, 1, RotationStatus.EN_ATTENTE));
            lient(l1, rotations);
            when(lotRepository.findByExamenId(EXAM)).thenReturn(List.of(l1));
            assignments(201L, 2, 2);
            assignments(204L, 2, 1);
            assignments(202L, 2, 0);
            assignments(203L, 2, 0);

            SuiviProgressionResponse r = service.getProgression(EXAM);

            assertThat(r.getLotOuvert()).isNotNull();
            assertThat(r.getLotOuvert().getNumeroLot()).isEqualTo(1);
            assertThat(r.getLotOuvert().getOuvertA()).isEqualTo(ouvert);
            assertThat(r.isLotTermine()).isFalse();

            var s58 = r.getStations().get(0);
            assertThat(s58.getStationId()).isEqualTo(58L);
            assertThat(s58.getGroupeEnCours()).isEqualTo(2);
            assertThat(s58.getRangEnCours()).isEqualTo(2);
            assertThat(s58.getEtudiantsNotes()).isEqualTo(3);   // 2 + 1
            assertThat(s58.getEtudiantsTotal()).isEqualTo(4);
            assertThat(s58.getGroupesTermines()).isEqualTo(1);
            assertThat(s58.getStatut()).isEqualTo("EN_COURS");

            // Le statut est LU. Aucune valeur « DEPASSEMENT » n'existe dans ce contrat.
            assertThat(r.getStations()).allSatisfy(s ->
                    assertThat(s.getStatut()).isIn("EN_ATTENTE", "EN_COURS", "TERMINE"));
        }

        /**
         * Le cas qui piégeait l'ancien tableau : des créneaux largement passés. L'ancien Suivi
         * affichait « dépassement — créneau écoulé, encore en cours ». Ici, le seul fait qui compte
         * est le statut stocké — et la veille ne change rien.
         */
        @Test
        @DisplayName("des créneaux de la VEILLE ne changent aucun statut")
        void progression_ignoreLHorloge() {
            Lot l1 = lot(LOT1, 1, LocalDateTime.now().minusDays(1));
            Rotation vieille = rot(201L, 58L, 1, 1, RotationStatus.EN_COURS);
            vieille.setDebutCreneau(LocalDateTime.now().minusDays(1));
            lient(l1, List.of(vieille));
            when(lotRepository.findByExamenId(EXAM)).thenReturn(List.of(l1));
            assignments(201L, 2, 0);

            SuiviProgressionResponse r = service.getProgression(EXAM);

            assertThat(r.getStations().get(0).getStatut()).isEqualTo("EN_COURS");
            assertThat(r.isLotTermine()).isFalse();
        }

        /**
         * #209 — MI-VAGUE : l'évaluateur a validé un groupe et n'a pas encore cliqué
         * « Groupe suivant » (valider n'avance plus). Aucune rotation EN_COURS à la station,
         * mais elle n'est PAS « en attente d'ouverture » : statut EN_COURS, groupeEnCours
         * null — le front rend « Entre deux groupes ». Lire EN_ATTENTE mentirait au
         * responsable (vérifié live avant d'être épinglé ici).
         */
        @Test
        @DisplayName("#209 : validé-pas-avancé → station EN_COURS avec groupeEnCours null (entre deux groupes)")
        void station_entreDeuxGroupes() {
            Lot l1 = lot(LOT1, 1, MAINTENANT.minusMinutes(5));
            lient(l1, List.of(
                    rot(201L, 58L, 1, 1, RotationStatus.TERMINE),
                    rot(204L, 58L, 2, 2, RotationStatus.EN_ATTENTE)));
            when(lotRepository.findByExamenId(EXAM)).thenReturn(List.of(l1));
            assignments(201L, 2, 2);
            assignments(204L, 2, 0);

            SuiviProgressionResponse r = service.getProgression(EXAM);

            var s58 = r.getStations().get(0);
            assertThat(s58.getStatut()).isEqualTo("EN_COURS");
            assertThat(s58.getGroupeEnCours()).isNull();
            assertThat(s58.getGroupesTermines()).isEqualTo(1);
        }

        /** Un groupe n'est fini que lorsqu'il a bouclé TOUTES les stations. */
        @Test
        @DisplayName("un groupe validé sur une seule station n'est pas compté comme terminé")
        void progression_groupeTermineExigeToutesLesStations() {
            Lot l1 = lot(LOT1, 1, LocalDateTime.now());
            lient(l1, List.of(
                    rot(201L, 58L, 1, 1, RotationStatus.TERMINE),
                    rot(202L, 59L, 2, 1, RotationStatus.EN_COURS)));
            when(lotRepository.findByExamenId(EXAM)).thenReturn(List.of(l1));
            assignments(201L, 1, 1);
            assignments(202L, 1, 0);

            SuiviProgressionResponse r = service.getProgression(EXAM);

            assertThat(r.getLotOuvert().getGroupesTotal()).isEqualTo(1);
            assertThat(r.getLotOuvert().getGroupesTermines()).isZero();
        }
    }

    // =========================================================================
    @Nested
    @DisplayName("L'alerte « lot terminé » et le lot suivant")
    class AlerteEtLotSuivant {

        @Test
        @DisplayName("toutes les rotations TERMINE et une vague reste : ALERTE + lot suivant proposé")
        void alerte_quandVagueFinieEtSuivanteDisponible() {
            Lot l1 = lot(LOT1, 1, LocalDateTime.now());
            Lot l2 = lot(LOT2, 2, null);
            lient(l1, List.of(
                    rot(201L, 58L, 1, 1, RotationStatus.TERMINE),
                    rot(202L, 59L, 1, 2, RotationStatus.TERMINE)));
            lient(l2, List.of(
                    rot(205L, 58L, 1, 1, RotationStatus.EN_ATTENTE),
                    rot(206L, 59L, 1, 2, RotationStatus.EN_ATTENTE)));
            when(lotRepository.findByExamenId(EXAM)).thenReturn(List.of(l1, l2));
            assignments(201L, 2, 2);
            assignments(202L, 2, 2);

            SuiviProgressionResponse r = service.getProgression(EXAM);

            assertThat(r.isLotTermine()).isTrue();
            assertThat(r.getLotSuivant()).isNotNull();
            assertThat(r.getLotSuivant().getNumeroLot()).isEqualTo(2);
            assertThat(r.getLotSuivant().isRotationsGenerees()).isTrue();
            // On continue d'afficher la vague qui vient de finir : le responsable doit voir CE
            // qu'il clôture, pas un tableau vide.
            assertThat(r.getLotOuvert().getNumeroLot()).isEqualTo(1);
        }

        @Test
        @DisplayName("dernière vague terminée : pas d'alerte, plus de lot suivant")
        void pasDAlerte_quandCEtaitLaDerniereVague() {
            Lot l1 = lot(LOT1, 1, LocalDateTime.now());
            lient(l1, List.of(rot(201L, 58L, 1, 1, RotationStatus.TERMINE)));
            when(lotRepository.findByExamenId(EXAM)).thenReturn(List.of(l1));
            assignments(201L, 2, 2);

            SuiviProgressionResponse r = service.getProgression(EXAM);

            assertThat(r.isLotTermine()).isFalse();
            assertThat(r.getLotSuivant()).isNull();
        }

        @Test
        @DisplayName("vague en cours : pas d'alerte, et le lot suivant reste annoncé sans être ouvrable")
        void pasDAlerte_tantQueLaVagueTourne() {
            Lot l1 = lot(LOT1, 1, LocalDateTime.now());
            Lot l2 = lot(LOT2, 2, null);
            lient(l1, List.of(
                    rot(201L, 58L, 1, 1, RotationStatus.TERMINE),
                    rot(202L, 59L, 1, 2, RotationStatus.EN_COURS)));
            lient(l2, List.of());
            when(lotRepository.findByExamenId(EXAM)).thenReturn(List.of(l1, l2));
            assignments(201L, 2, 2);
            assignments(202L, 2, 1);

            SuiviProgressionResponse r = service.getProgression(EXAM);

            assertThat(r.isLotTermine()).isFalse();
            // Le lot 2 est annoncé mais sans planning : le bouton doit le DIRE, pas planter.
            assertThat(r.getLotSuivant().isRotationsGenerees()).isFalse();
        }

        /**
         * #252 — la mesure est SERVEUR. Le navigateur et le bean {@code Clock} ne sont pas dans
         * le même fuseau (conteneur CEST, Clock Africa/Tunis, ADR-0010) : une soustraction faite
         * dans le front afficherait +1:00:00 dès l'ouverture d'une vague.
         */
        @Test
        @DisplayName("#252 : la durée écoulée est calculée côté serveur, en secondes")
        void ecouleSec_calculeParLeServeur() {
            Lot l1 = lot(LOT1, 1, MAINTENANT.minusMinutes(7));
            lient(l1, List.of(rot(201L, 58L, 1, 1, RotationStatus.EN_COURS)));
            when(lotRepository.findByExamenId(EXAM)).thenReturn(List.of(l1));
            assignments(201L, 2, 0);

            SuiviProgressionResponse r = service.getProgression(EXAM);

            assertThat(r.getLotOuvert().getEcouleSec()).isEqualTo(7 * 60);
        }

        /**
         * ⛔ <b>LA GARDE ANTI-PLAFOND.</b> Une vague terminée ne doit plus rien chronométrer :
         * sinon le compteur grimperait pendant toute l'attente entre deux vagues et recréerait
         * le « +42:16 et croissant » que ce lot de tickets supprime — le plafond réinventé sous
         * un autre nom. Vague finie ⇒ pas de chronomètre, mais l'alerte « lot terminé ».
         */
        @Test
        @DisplayName("#252 : une vague TERMINÉE ne chronomètre plus rien (anti-plafond)")
        void ecouleSec_sArreteQuandLaVagueEstFinie() {
            Lot l1 = lot(LOT1, 1, MAINTENANT.minusHours(3));   // ouverte il y a 3 h
            Lot l2 = lot(LOT2, 2, null);
            lient(l1, List.of(rot(201L, 58L, 1, 1, RotationStatus.TERMINE)));
            lient(l2, List.of(rot(205L, 58L, 1, 1, RotationStatus.EN_ATTENTE)));
            when(lotRepository.findByExamenId(EXAM)).thenReturn(List.of(l1, l2));
            assignments(201L, 2, 2);

            SuiviProgressionResponse r = service.getProgression(EXAM);

            assertThat(r.getLotOuvert().getEcouleSec())
                    .as("une vague finie ne doit pas continuer à compter — ce serait le plafond")
                    .isNull();
            assertThat(r.isLotTermine()).isTrue();
        }

        /**
         * #252 — une vague ouverte avant la migration V9 n'a pas d'horodatage. On expose null et
         * le client affiche « — » : inventer une ancre (launched_at, un créneau) reconstruirait
         * exactement la mesure fausse que ce ticket supprime.
         */
        @Test
        @DisplayName("vague ouverte avant V9 : ouvertA null, aucune ancre inventée")
        void ouvertA_nullSansRattrapage() {
            Lot l1 = lot(LOT1, 1, null);
            lient(l1, List.of(rot(201L, 58L, 1, 1, RotationStatus.EN_COURS)));
            when(lotRepository.findByExamenId(EXAM)).thenReturn(List.of(l1));
            assignments(201L, 1, 0);

            SuiviProgressionResponse r = service.getProgression(EXAM);

            assertThat(r.getLotOuvert().getOuvertA()).isNull();
        }

        @Test
        @DisplayName("aucune vague ouverte : tableau vide, pas d'erreur")
        void aucuneVagueOuverte() {
            Lot l1 = lot(LOT1, 1, null);
            lient(l1, List.of(rot(201L, 58L, 1, 1, RotationStatus.EN_ATTENTE)));
            when(lotRepository.findByExamenId(EXAM)).thenReturn(List.of(l1));

            SuiviProgressionResponse r = service.getProgression(EXAM);

            assertThat(r.getLotOuvert()).isNull();
            assertThat(r.getStations()).isEmpty();
            assertThat(r.getLotSuivant().getNumeroLot()).isEqualTo(1);
        }
    }
}
