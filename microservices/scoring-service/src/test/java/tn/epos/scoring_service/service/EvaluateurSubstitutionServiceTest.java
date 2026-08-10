package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.dto.SubstitutionResult;
import tn.epos.scoring_service.entities.*;
import tn.epos.scoring_service.repositories.IEvaluateurSubstitutionRepository;
import tn.epos.scoring_service.repositories.ILotRepository;
import tn.epos.scoring_service.repositories.IRotationRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * ADR-0017 — la suppléance d'un évaluateur en cours d'épreuve.
 *
 * <p>Ce qui est pinné ici tient en une phrase : <b>on transfère le travail qui
 * reste, et on ne réécrit pas celui qui est fait.</b> C'est la garantie qui rend
 * la suppléance acceptable — sans elle, un remplacement repeindrait les notes du
 * partant à son propre nom.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EvaluateurSubstitutionService (ADR-0017)")
class EvaluateurSubstitutionServiceTest {

    private static final Long LOT = 266L;
    private static final Long STATION = 87L;
    private static final Long STATION_AUTRE = 88L;
    private static final Long PARTANT = 3L;
    private static final Long REMPLACANT = 2L;
    private static final Long RESPONSABLE = 9L;
    private static final String MOTIF = "Urgence familiale a 11h";

    @Mock private IRotationRepository rotationRepository;
    @Mock private IEvaluateurSubstitutionRepository substitutionRepository;
    /** #274 — le lot n'est chargé que pour résoudre la matière ; permissif ici. */
    @Mock private ILotRepository lotRepository;
    @Mock private MatiereAccessGuard matiereAccessGuard;

    private EvaluateurSubstitutionService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-29T10:00:00Z"), ZoneId.of("Africa/Tunis"));
        service = new EvaluateurSubstitutionService(rotationRepository, substitutionRepository,
                clock, lotRepository, matiereAccessGuard);
    }

    private Rotation rotation(long id, Long stationId, Long evaluateurId, RotationStatus statut) {
        Rotation r = new Rotation();
        r.setId(id);
        r.setStationId(stationId);
        r.setEvaluateurId(evaluateurId);
        r.setStatut(statut);
        return r;
    }

    @Test
    @DisplayName("Le travail RESTANT change de main ; le travail FINI garde son évaluateur")
    void remplacer_devraitTransfererSeulementLeTravailRestant() {
        Rotation finie   = rotation(1L, STATION, PARTANT, RotationStatus.TERMINE);
        Rotation enCours = rotation(2L, STATION, PARTANT, RotationStatus.EN_COURS);
        Rotation aVenir  = rotation(3L, STATION, PARTANT, RotationStatus.EN_ATTENTE);
        when(rotationRepository.findByStudentGroup_Lot_Id(LOT))
                .thenReturn(List.of(finie, enCours, aVenir));

        SubstitutionResult r = service.remplacer(LOT, STATION, REMPLACANT, MOTIF, RESPONSABLE);

        // §3 ratifié : le groupe EN COURS est inclus — quelqu'un doit le finir.
        assertThat(enCours.getEvaluateurId()).isEqualTo(REMPLACANT);
        assertThat(aVenir.getEvaluateurId()).isEqualTo(REMPLACANT);
        // On ne réécrit pas l'histoire.
        assertThat(finie.getEvaluateurId()).isEqualTo(PARTANT);

        assertThat(r.rotationsTransferees()).isEqualTo(2);
        assertThat(r.rotationsConservees()).isEqualTo(1);
        assertThat(r.ancienEvaluateur()).isEqualTo(PARTANT);
        assertThat(r.nouvelEvaluateur()).isEqualTo(REMPLACANT);
    }

    @Test
    @DisplayName("La suppléance est TRACÉE : qui, quoi, combien, pourquoi, décidé par qui")
    void remplacer_devraitLaisserUneTrace() {
        when(rotationRepository.findByStudentGroup_Lot_Id(LOT))
                .thenReturn(List.of(rotation(1L, STATION, PARTANT, RotationStatus.EN_COURS)));

        service.remplacer(LOT, STATION, REMPLACANT, MOTIF, RESPONSABLE);

        ArgumentCaptor<EvaluateurSubstitution> cap =
                ArgumentCaptor.forClass(EvaluateurSubstitution.class);
        verify(substitutionRepository).save(cap.capture());
        EvaluateurSubstitution t = cap.getValue();
        assertThat(t.getLotId()).isEqualTo(LOT);
        assertThat(t.getStationId()).isEqualTo(STATION);
        assertThat(t.getAncienEvaluateur()).isEqualTo(PARTANT);
        assertThat(t.getNouvelEvaluateur()).isEqualTo(REMPLACANT);
        assertThat(t.getRotationsTransferees()).isEqualTo(1);
        assertThat(t.getMotif()).isEqualTo(MOTIF);
        // Le responsable qui décide, jamais l'évaluateur (ADR-0017 §1).
        assertThat(t.getDecidePar()).isEqualTo(RESPONSABLE);
        assertThat(t.getSurvenuA()).isNotNull();
    }

    /**
     * Avant le démarrage de la vague il n'y a AUCUNE rotation : réaffecter la
     * station suffit (ADR-0017 §2). Le message doit le dire, sinon le responsable
     * cherchera un bouton qui n'a pas lieu d'exister.
     */
    @Test
    @DisplayName("Vague pas démarrée → explique qu'il suffit de réaffecter la station")
    void remplacer_avantDemarrage_devraitOrienterVersLaReaffectation() {
        when(rotationRepository.findByStudentGroup_Lot_Id(LOT)).thenReturn(List.of());

        assertThatThrownBy(() -> service.remplacer(LOT, STATION, REMPLACANT, MOTIF, RESPONSABLE))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Réaffectez la station");
    }

    @Test
    @DisplayName("Remplacer quelqu'un par lui-même est refusé")
    void remplacer_parLuiMeme_devraitEtreRefuse() {
        when(rotationRepository.findByStudentGroup_Lot_Id(LOT))
                .thenReturn(List.of(rotation(1L, STATION, PARTANT, RotationStatus.EN_COURS)));

        assertThatThrownBy(() -> service.remplacer(LOT, STATION, PARTANT, MOTIF, RESPONSABLE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("tient déjà cette station");
    }

    /**
     * Version locale de #265 : les stations d'un lot tournent SIMULTANÉMENT, donc
     * une personne ne peut pas en tenir deux. Sans ce garde, la suppléance
     * créerait le double engagement que #265 interdit au lancement.
     */
    @Test
    @DisplayName("Un remplaçant déjà sur une autre station du MÊME lot est refusé")
    void remplacer_parQuelquUnDejaOccupe_devraitEtreRefuse() {
        when(rotationRepository.findByStudentGroup_Lot_Id(LOT)).thenReturn(List.of(
                rotation(1L, STATION, PARTANT, RotationStatus.EN_COURS),
                rotation(2L, STATION_AUTRE, REMPLACANT, RotationStatus.EN_COURS)));

        assertThatThrownBy(() -> service.remplacer(LOT, STATION, REMPLACANT, MOTIF, RESPONSABLE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ne peut pas en servir deux");
    }

    @Test
    @DisplayName("Un remplaçant ayant TERMINÉ une autre station reste éligible")
    void remplacer_parQuelquUnLibere_devraitEtreAccepte() {
        Rotation aReprendre = rotation(1L, STATION, PARTANT, RotationStatus.EN_ATTENTE);
        when(rotationRepository.findByStudentGroup_Lot_Id(LOT)).thenReturn(List.of(
                aReprendre,
                // il a fini ailleurs : il est libre, pas double-engagé
                rotation(2L, STATION_AUTRE, REMPLACANT, RotationStatus.TERMINE)));

        SubstitutionResult r = service.remplacer(LOT, STATION, REMPLACANT, MOTIF, RESPONSABLE);

        assertThat(r.rotationsTransferees()).isEqualTo(1);
        assertThat(aReprendre.getEvaluateurId()).isEqualTo(REMPLACANT);
    }

    @Test
    @DisplayName("Station entièrement terminée → rien à reprendre, et on le dit")
    void remplacer_toutTermine_devraitEtreRefuse() {
        when(rotationRepository.findByStudentGroup_Lot_Id(LOT))
                .thenReturn(List.of(rotation(1L, STATION, PARTANT, RotationStatus.TERMINE)));

        assertThatThrownBy(() -> service.remplacer(LOT, STATION, REMPLACANT, MOTIF, RESPONSABLE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("déjà terminés");
        verify(substitutionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Les rotations d'une AUTRE station du lot ne sont pas touchées")
    void remplacer_neDoitPasToucherLesAutresStations() {
        Rotation cible  = rotation(1L, STATION, PARTANT, RotationStatus.EN_COURS);
        Rotation voisine = rotation(2L, STATION_AUTRE, 6L, RotationStatus.EN_COURS);
        when(rotationRepository.findByStudentGroup_Lot_Id(LOT)).thenReturn(List.of(cible, voisine));

        service.remplacer(LOT, STATION, REMPLACANT, MOTIF, RESPONSABLE);

        assertThat(cible.getEvaluateurId()).isEqualTo(REMPLACANT);
        assertThat(voisine.getEvaluateurId()).isEqualTo(6L);
    }
}
