package tn.epos.scoring_service.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.common.exception.BusinessException;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.entities.ExamGrilleSnapshot;
import tn.epos.scoring_service.entities.ExamItemSnapshot;
import tn.epos.scoring_service.entities.ExamStationSnapshot;
import tn.epos.scoring_service.repositories.ExamGrilleSnapshotRepository;
import tn.epos.scoring_service.repositories.ExamItemSnapshotRepository;
import tn.epos.scoring_service.repositories.ExamStationSnapshotRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * ADR-0015 — lecture du snapshot : <b>strict à l'écriture, dégradé à l'affichage</b>.
 *
 * <p>L'asymétrie est la doctrine, pas un détail d'implémentation : une note fausse persistée est un
 * dégât permanent, un intitulé manquant ne l'est pas. Ces tests vérifient que les deux chemins ne
 * peuvent pas être confondus.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExamDefinitionSnapshotService — ADR-0015, résolution")
class ExamDefinitionSnapshotServiceTest {

    @Mock private ExamStationSnapshotRepository stationSnapshotRepository;
    @Mock private ExamItemSnapshotRepository itemSnapshotRepository;
    @Mock private ExamServiceClient examServiceClient;
    @Mock private ExamDefinitionMaterialiser materialiser;
    @Mock private ExamGrilleSnapshotRepository grilleSnapshotRepository;
    @InjectMocks private ExamDefinitionSnapshotService service;

    private static final Long EXAMEN = 2L;
    private static final Long STATION = 5L;
    private static final Long GRILLE = 5L;
    private static final String NOM = "Identification d'un principe actif";

    private static ExamStationSnapshot station(String nom) {
        return ExamStationSnapshot.builder().examenId(EXAMEN).stationId(STATION).nom(nom).build();
    }

    private static ExamItemSnapshot item(Long id, double pond, String type) {
        return ExamItemSnapshot.builder()
                .examenId(EXAMEN).grilleId(GRILLE).itemId(id).ponderation(pond).type(type).build();
    }

    @Nested
    @DisplayName("resolveStationNom() — chemin STRICT")
    class Strict {

        @Test
        @DisplayName("station déjà figée : lit le snapshot, aucun appel réseau")
        void litLeSnapshotSansReseau() {
            when(stationSnapshotRepository.findByStationId(STATION)).thenReturn(Optional.of(station(NOM)));

            assertThat(service.resolveStationNom(EXAMEN, STATION)).isEqualTo(NOM);

            // C'est tout l'intérêt de l'ADR : après la première matérialisation,
            // exam-service peut rester indisponible sans effet.
            verifyNoInteractions(materialiser, examServiceClient);
        }

        @Test
        @DisplayName("station non figée : matérialise à la première utilisation")
        void materialiseALaPremiereUtilisation() {
            when(stationSnapshotRepository.findByStationId(STATION)).thenReturn(Optional.empty());
            when(materialiser.materialiseStation(EXAMEN, STATION)).thenReturn(station(NOM));

            assertThat(service.resolveStationNom(EXAMEN, STATION)).isEqualTo(NOM);
            verify(materialiser).materialiseStation(EXAMEN, STATION);
        }

        @Test
        @DisplayName("échec de matérialisation : propage — jamais « Station <id> »")
        void propageLEchec() {
            when(stationSnapshotRepository.findByStationId(STATION)).thenReturn(Optional.empty());
            when(materialiser.materialiseStation(EXAMEN, STATION))
                    .thenThrow(new BusinessException("exam-service injoignable"));

            assertThatThrownBy(() -> service.resolveStationNom(EXAMEN, STATION))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("resolveStationNomPourAffichage() — chemin DÉGRADÉ")
    class Affichage {

        @Test
        @DisplayName("station figée : renvoie le vrai intitulé sans consulter la santé du service")
        void stationFigeeNeConsultePasLaSante() {
            when(stationSnapshotRepository.findByStationId(STATION)).thenReturn(Optional.of(station(NOM)));

            assertThat(service.resolveStationNomPourAffichage(EXAMEN, STATION)).isEqualTo(NOM);
            verify(examServiceClient, never()).estProbablementInjoignable();
        }

        /**
         * Le court-circuit mesuré le 2026-07-20 : sans lui, chaque session non figée payait ~3,07 s
         * de délai réseau, portant le dashboard à 31–61 s alors que le client mobile abandonne à 20 s.
         * L'évaluateur ne voyait donc JAMAIS le tableau dégradé, seulement une erreur réseau.
         */
        @Test
        @DisplayName("non figée + service réputé injoignable : dégrade SANS retenter le réseau")
        void degradeSansRetenter() {
            when(stationSnapshotRepository.findByStationId(STATION)).thenReturn(Optional.empty());
            when(examServiceClient.estProbablementInjoignable()).thenReturn(true);

            assertThat(service.resolveStationNomPourAffichage(EXAMEN, STATION))
                    .isEqualTo(ExamDefinitionSnapshotService.NOM_INDISPONIBLE);

            verifyNoInteractions(materialiser);
        }

        @Test
        @DisplayName("échec de résolution : dégrade au lieu de tuer tout le tableau de bord")
        void degradeAuLieuDeTuerLeTableau() {
            when(stationSnapshotRepository.findByStationId(STATION)).thenReturn(Optional.empty());
            when(examServiceClient.estProbablementInjoignable()).thenReturn(false);
            when(materialiser.materialiseStation(EXAMEN, STATION))
                    .thenThrow(new BusinessException("exam-service injoignable"));

            assertThat(service.resolveStationNomPourAffichage(EXAMEN, STATION))
                    .isEqualTo(ExamDefinitionSnapshotService.NOM_INDISPONIBLE);
        }

        /**
         * Le marqueur doit rester NI plausible NI vide : « Station 5 » se lit comme un vrai intitulé
         * (c'est le repli que l'ADR supprime) et {@code null}/vide rend un libellé vide côté mobile
         * ({@code stationNom ?? ''}) — la même classe de fuite de placeholder.
         */
        @Test
        @DisplayName("le marqueur dégradé n'est ni vide ni « Station <id> »")
        void marqueurNiVideNiPlausible() {
            String m = ExamDefinitionSnapshotService.NOM_INDISPONIBLE;
            assertThat(m).isNotBlank();
            assertThat(m).doesNotMatch("(?i)^station\\s*\\d+$");
        }
    }

    @Nested
    @DisplayName("resolveGrille() — chemin STRICT (utilisé par le chemin de notation)")
    class ResolveGrille {

        private static ExamGrilleSnapshot grilleSnap() {
            return ExamGrilleSnapshot.builder()
                    .examenId(EXAMEN).stationId(STATION).grilleId(GRILLE)
                    .nom(NOM).noteMax(20.0).itemsJson("[]").build();
        }

        @Test
        @DisplayName("grille déjà figée : lit le snapshot, aucun appel réseau")
        void litLeSnapshotSansReseau() {
            when(grilleSnapshotRepository.findByStationId(STATION)).thenReturn(Optional.of(grilleSnap()));

            assertThat(service.resolveGrille(EXAMEN, STATION).getNom()).isEqualTo(NOM);
            verifyNoInteractions(materialiser, examServiceClient);
        }

        @Test
        @DisplayName("grille non figée : matérialise à la première utilisation")
        void materialiseALaPremiereUtilisation() {
            when(grilleSnapshotRepository.findByStationId(STATION)).thenReturn(Optional.empty());
            when(materialiser.materialiseGrille(EXAMEN, STATION)).thenReturn(grilleSnap());

            assertThat(service.resolveGrille(EXAMEN, STATION).getNom()).isEqualTo(NOM);
            verify(materialiser).materialiseGrille(EXAMEN, STATION);
        }

        @Test
        @DisplayName("échec de matérialisation : propage — jamais de repli silencieux (grille = chemin d'écriture)")
        void propageLEchec() {
            when(grilleSnapshotRepository.findByStationId(STATION)).thenReturn(Optional.empty());
            when(materialiser.materialiseGrille(EXAMEN, STATION))
                    .thenThrow(new BusinessException("exam-service injoignable"));

            assertThatThrownBy(() -> service.resolveGrille(EXAMEN, STATION))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("resolveItems()")
    class Items {

        @Test
        @DisplayName("items déjà figés : indexés par itemId, sans matérialisation")
        void litLesItemsFiges() {
            when(itemSnapshotRepository.findByGrilleId(GRILLE))
                    .thenReturn(List.of(item(17L, 5d, "BINAIRE"), item(19L, 6d, "NUMERIQUE")));

            Map<Long, ExamItemSnapshot> out = service.resolveItems(EXAMEN, GRILLE);

            assertThat(out).containsOnlyKeys(17L, 19L);
            assertThat(out.get(19L).getPonderation()).isEqualTo(6d);
            verifyNoInteractions(materialiser);
        }

        @Test
        @DisplayName("aucun item figé : matérialise")
        void materialiseSiVide() {
            when(itemSnapshotRepository.findByGrilleId(GRILLE)).thenReturn(List.of());
            when(materialiser.materialiseItems(EXAMEN, GRILLE)).thenReturn(List.of(item(17L, 5d, "BINAIRE")));

            assertThat(service.resolveItems(EXAMEN, GRILLE)).containsOnlyKeys(17L);
            verify(materialiser).materialiseItems(EXAMEN, GRILLE);
        }
    }

    @Nested
    @DisplayName("weigh() — l'unique définition de l'arithmétique")
    class Weigh {

        @Test
        @DisplayName("item connu : applique la pondération figée")
        void appliqueLaPonderation() {
            Map<Long, ExamItemSnapshot> items = Map.of(17L, item(17L, 5d, "BINAIRE"));

            // BINAIRE : valeur 1 vaut la pondération entière (5), pas 1 —
            // c'est exactement ce qu'une panne transformait en note brute.
            assertThat(service.weigh(items, 17L, 1f)).isEqualTo(5f);
        }

        /**
         * La garde de feuille devient LOCALE et INCONDITIONNELLE : la présence dans le snapshot fait
         * autorité. Un item absent n'est pas notable — le noter brut est précisément ce qui faisait
         * double-compter un critère parent.
         */
        @Test
        @DisplayName("item absent du snapshot : refuse au lieu de noter brut")
        void refuseUnItemInconnu() {
            Map<Long, ExamItemSnapshot> items = Map.of(17L, item(17L, 5d, "BINAIRE"));

            assertThatThrownBy(() -> service.weigh(items, 999L, 1f))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("999");
        }
    }

    /** #183 « dé-lancer » doit purger le snapshot, sinon une grille modifiée serait notée sur la copie périmée. */
    @Test
    @DisplayName("invalidateExam() purge les TROIS tables du snapshot")
    void invalidatePurgeLesTroisTables() {
        service.invalidateExam(EXAMEN);

        verify(stationSnapshotRepository).deleteByExamenId(EXAMEN);
        verify(itemSnapshotRepository).deleteByExamenId(EXAMEN);
        verify(grilleSnapshotRepository).deleteByExamenId(EXAMEN);
    }
}
