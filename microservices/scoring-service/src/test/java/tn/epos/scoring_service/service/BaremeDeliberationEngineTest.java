package tn.epos.scoring_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.scoring_service.entities.BaremeDeliberation;
import tn.epos.scoring_service.entities.BaremeDeliberationOperation;
import tn.epos.scoring_service.entities.ExamGrilleSnapshot;
import tn.epos.scoring_service.entities.ExamItemSnapshot;
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.entities.TypeOperationBareme;
import tn.epos.scoring_service.repositories.ExamGrilleSnapshotRepository;
import tn.epos.scoring_service.repositories.ExamItemSnapshotRepository;
import tn.epos.scoring_service.repositories.IBaremeDeliberationOperationRepository;
import tn.epos.scoring_service.repositories.IBaremeDeliberationRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * ADR-0030 D4 (#361) — l'arithmétique du recalcul de présentation, sur des
 * exemples CALCULÉS À LA MAIN (le mock qui stuberait le calcul ne testerait
 * rien — leçon des suites réajustement).
 *
 * <p>Fixture : station 101, grille 201, note_max 20. Trois feuilles :
 * item 1 NUMERIQUE valeurMax 8 · item 2 BINAIRE pondération 5 (valeurMax null,
 * niché sous un parent dans items_json — exerce la récursion sousCriteres) ·
 * item 3 NUMERIQUE valeurMax 7. Notation : score_final 15.5 (volontairement ≠
 * de la somme des items — un réajustement ADR-0013 au TOTAL doit survivre au
 * delta), valeurs saisies : item1=6, item2=1, item3 jamais saisi.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BaremeDeliberationEngine — arithmétique des deux dénominateurs (ADR-0030 D4)")
class BaremeDeliberationEngineTest {

    @Mock
    private IBaremeDeliberationRepository baremeRepository;
    @Mock
    private IBaremeDeliberationOperationRepository operationRepository;
    @Mock
    private ExamItemSnapshotRepository itemSnapshotRepository;
    @Mock
    private ExamGrilleSnapshotRepository grilleSnapshotRepository;

    private BaremeDeliberationEngine engine;

    private static final long EXAMEN = 77L;
    private static final String ITEMS_JSON = """
            [
              {"id": 1, "type": "NUMERIQUE", "valeurMax": 8.0, "ponderation": 1.0},
              {"id": 10, "type": "NUMERIQUE", "valeurMax": null,
               "sousCriteres": [
                 {"id": 2, "type": "BINAIRE", "valeurMax": null, "ponderation": 5.0},
                 {"id": 3, "type": "NUMERIQUE", "valeurMax": 7.0, "ponderation": 1.0}
               ]}
            ]""";

    @BeforeEach
    void setUp() {
        engine = new BaremeDeliberationEngine(baremeRepository, operationRepository,
                itemSnapshotRepository, grilleSnapshotRepository, new ObjectMapper());
    }

    private static ExamGrilleSnapshot grille() {
        return ExamGrilleSnapshot.builder()
                .examenId(EXAMEN).stationId(101L).grilleId(201L)
                .nom("Station test").noteMax(20.0).itemsJson(ITEMS_JSON)
                .build();
    }

    private static ExamItemSnapshot item(long itemId, String type, double ponderation) {
        return ExamItemSnapshot.builder()
                .examenId(EXAMEN).grilleId(201L).itemId(itemId)
                .type(type).ponderation(ponderation)
                .build();
    }

    private static Notation notation(Float scoreFinal) {
        Notation n = new Notation();
        n.setId(1L);
        n.setStationId(101L);
        n.setGrilleId(201L);
        n.setScore_final(scoreFinal);
        return n;
    }

    private void stubExam(BaremeDeliberationOperation... ops) {
        BaremeDeliberation version = BaremeDeliberation.builder()
                .id(9L).examenId(EXAMEN).version(1).motif("m").creePar(5L).build();
        when(baremeRepository.findTopByExamenIdOrderByVersionDesc(EXAMEN))
                .thenReturn(Optional.of(version));
        when(operationRepository.findByBaremeId(9L)).thenReturn(List.of(ops));
        when(grilleSnapshotRepository.findByExamenId(EXAMEN)).thenReturn(List.of(grille()));
        when(itemSnapshotRepository.findByExamenId(EXAMEN)).thenReturn(List.of(
                item(1L, "NUMERIQUE", 1.0), item(2L, "BINAIRE", 5.0), item(3L, "NUMERIQUE", 1.0)));
    }

    private static BaremeDeliberationOperation op(
            TypeOperationBareme type, Long itemId, Long stationId, Double echelle) {
        return BaremeDeliberationOperation.builder()
                .baremeId(9L).type(type).cibleItemId(itemId)
                .cibleStationId(stationId).nouvelleEchelle(echelle)
                .build();
    }

    @Nested
    @DisplayName("chargerCourant — dénominateur délibéré par station")
    class Denominateurs {

        @Test
        @DisplayName("Aucun barème → empty (pas de barème ≠ barème vide)")
        void sansBareme_empty() {
            when(baremeRepository.findTopByExamenIdOrderByVersionDesc(EXAMEN))
                    .thenReturn(Optional.empty());
            assertThat(engine.chargerCourant(EXAMEN)).isEmpty();
        }

        @Test
        @DisplayName("Version VIDE → max délibéré == note_max (retour à l'origine exact)")
        void versionVide_identique() {
            stubExam();
            var b = engine.chargerCourant(EXAMEN).orElseThrow();
            assertThat(b.maxDelibereParStation()).containsEntry(101L, 20.0);
            assertThat(b.maxOriginalParStation()).containsEntry(101L, 20.0);
        }

        @Test
        @DisplayName("EXCLURE_CRITERE (BINAIRE pond. 5) → max 20 − 5 = 15")
        void exclusionCritere_binaire() {
            stubExam(op(TypeOperationBareme.EXCLURE_CRITERE, 2L, null, null));
            var b = engine.chargerCourant(EXAMEN).orElseThrow();
            assertThat(b.maxDelibereParStation()).containsEntry(101L, 15.0);
        }

        @Test
        @DisplayName("REPONDERER critère 8 → 4 → max 20 + (4 − 8) = 16")
        void reponderationCritere() {
            stubExam(op(TypeOperationBareme.REPONDERER, 1L, null, 4.0));
            var b = engine.chargerCourant(EXAMEN).orElseThrow();
            assertThat(b.maxDelibereParStation()).containsEntry(101L, 16.0);
        }

        @Test
        @DisplayName("EXCLURE_STATION → la station sort de la carte des max délibérés")
        void exclusionStation() {
            stubExam(op(TypeOperationBareme.EXCLURE_STATION, null, 101L, null));
            var b = engine.chargerCourant(EXAMEN).orElseThrow();
            assertThat(b.maxDelibereParStation()).doesNotContainKey(101L);
            assertThat(b.stationsExclues()).containsExactly(101L);
            // le dénominateur ORIGINAL, lui, reste entier — les deux lectures.
            assertThat(b.maxOriginalParStation()).containsEntry(101L, 20.0);
        }

        @Test
        @DisplayName("REPONDERER station 20 → 10 → max délibéré = 10")
        void reponderationStation() {
            stubExam(op(TypeOperationBareme.REPONDERER, null, 101L, 10.0));
            var b = engine.chargerCourant(EXAMEN).orElseThrow();
            assertThat(b.maxDelibereParStation()).containsEntry(101L, 10.0);
        }
    }

    @Nested
    @DisplayName("scoreDelibere — delta depuis score_final (jamais une re-somme)")
    class Scores {

        private static final Map<Long, Float> VALEURS = Map.of(1L, 6f, 2L, 1f);

        @Test
        @DisplayName("Version vide → score délibéré == score_final (15.5)")
        void versionVide() {
            stubExam();
            var b = engine.chargerCourant(EXAMEN).orElseThrow();
            assertThat(engine.scoreDelibere(b, notation(15.5f), VALEURS)).isEqualTo(15.5f);
        }

        @Test
        @DisplayName("EXCLURE_CRITERE BINAIRE (1×5) → 15.5 − 5 = 10.5")
        void exclusionBinaire() {
            stubExam(op(TypeOperationBareme.EXCLURE_CRITERE, 2L, null, null));
            var b = engine.chargerCourant(EXAMEN).orElseThrow();
            assertThat(engine.scoreDelibere(b, notation(15.5f), VALEURS)).isEqualTo(10.5f);
        }

        @Test
        @DisplayName("EXCLURE_CRITERE d'un critère JAMAIS saisi → delta nul (15.5)")
        void exclusionCritereNonSaisi() {
            stubExam(op(TypeOperationBareme.EXCLURE_CRITERE, 3L, null, null));
            var b = engine.chargerCourant(EXAMEN).orElseThrow();
            assertThat(engine.scoreDelibere(b, notation(15.5f), VALEURS)).isEqualTo(15.5f);
            // ...mais son maximum sort bien du dénominateur : 20 − 7 = 13.
            assertThat(b.maxDelibereParStation()).containsEntry(101L, 13.0);
        }

        @Test
        @DisplayName("REPONDERER NUMERIQUE 6/8 vers 4 → 15.5 + (3 − 6) = 12.5")
        void reponderationNumerique() {
            stubExam(op(TypeOperationBareme.REPONDERER, 1L, null, 4.0));
            var b = engine.chargerCourant(EXAMEN).orElseThrow();
            assertThat(engine.scoreDelibere(b, notation(15.5f), VALEURS)).isEqualTo(12.5f);
        }

        @Test
        @DisplayName("REPONDERER station 20 → 10 : 15.5/20×10 = 7.75")
        void reponderationStation() {
            stubExam(op(TypeOperationBareme.REPONDERER, null, 101L, 10.0));
            var b = engine.chargerCourant(EXAMEN).orElseThrow();
            assertThat(engine.scoreDelibere(b, notation(15.5f), VALEURS)).isEqualTo(7.75f);
        }

        @Test
        @DisplayName("Station EXCLUE → null (l'appelant la sort des totaux)")
        void stationExclue() {
            stubExam(op(TypeOperationBareme.EXCLURE_STATION, null, 101L, null));
            var b = engine.chargerCourant(EXAMEN).orElseThrow();
            assertThat(engine.scoreDelibere(b, notation(15.5f), VALEURS)).isNull();
        }

        @Test
        @DisplayName("score_final null → null (rien à recalculer honnêtement)")
        void scoreNull() {
            stubExam();
            var b = engine.chargerCourant(EXAMEN).orElseThrow();
            assertThat(engine.scoreDelibere(b, notation(null), VALEURS)).isNull();
        }

        @Test
        @DisplayName("Le delta ne descend jamais sous 0 (exclusion > score stocké)")
        void clampZero() {
            stubExam(op(TypeOperationBareme.EXCLURE_CRITERE, 2L, null, null));
            var b = engine.chargerCourant(EXAMEN).orElseThrow();
            // score_final 3.0 < contribution 5 du critère exclu → 0, pas −2.
            assertThat(engine.scoreDelibere(b, notation(3f), VALEURS)).isEqualTo(0f);
        }
    }

    @Nested
    @DisplayName("valeurMaxParItem — parse items_json (récursif, tolérant)")
    class ValeurMax {

        @Test
        @DisplayName("Feuilles ET sous-critères lus ; valeurMax null absent de la carte")
        void parseRecursif() {
            Map<Long, Double> maxes = engine.valeurMaxParItem(List.of(grille()));
            assertThat(maxes).containsEntry(1L, 8.0).containsEntry(3L, 7.0)
                    .doesNotContainKey(2L).doesNotContainKey(10L);
        }

        @Test
        @DisplayName("items_json illisible → carte vide, pas d'exception (posture #355)")
        void jsonCorrompu() {
            ExamGrilleSnapshot corrompue = grille();
            corrompue.setItemsJson("{pas du json");
            assertThat(engine.valeurMaxParItem(List.of(corrompue))).isEmpty();
        }

        @Test
        @DisplayName("maxDeItem : BINAIRE → pondération ; NUMERIQUE → valeurMax ; inconnu → null")
        void maxParType() {
            Map<Long, Double> maxes = engine.valeurMaxParItem(List.of(grille()));
            assertThat(engine.maxDeItem(item(2L, "BINAIRE", 5.0), maxes)).isEqualTo(5.0);
            assertThat(engine.maxDeItem(item(1L, "NUMERIQUE", 1.0), maxes)).isEqualTo(8.0);
            assertThat(engine.maxDeItem(item(99L, "NUMERIQUE", 1.0), maxes)).isNull();
            assertThat(engine.maxDeItem(null, maxes)).isNull();
        }
    }
}
