package tn.epos.scoring_service.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ADR-0015 — écriture du snapshot de définition.
 *
 * <p>Ce qui est vérifié ici n'est pas « les lignes s'exécutent » mais les deux règles que la classe
 * existe pour tenir : <b>on ne fige jamais une valeur de repli</b> (échec amont ⇒ rien d'écrit), et
 * <b>une course perdue relit le gagnant</b> au lieu de propager l'erreur.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExamDefinitionMaterialiser — ADR-0015, écriture write-once")
class ExamDefinitionMaterialiserTest {

    @Mock  private ExamServiceClient examServiceClient;
    @Mock  private ExamStationSnapshotRepository stationSnapshotRepository;
    @Mock  private ExamItemSnapshotRepository itemSnapshotRepository;
    @Mock private ExamGrilleSnapshotRepository grilleSnapshotRepository;
    @InjectMocks private ExamDefinitionMaterialiser materialiser;

    private static final Long EXAMEN = 2L;
    private static final Long STATION = 5L;
    private static final Long GRILLE = 5L;
    private static final String NOM = "Identification d'un principe actif";

    @Nested
    @DisplayName("materialiseStation()")
    class Station {

        @Test
        @DisplayName("fige le nom renvoyé par la variante STRICTE du client")
        void figeLeNomStrict() {
            when(examServiceClient.getStationNomStrict(STATION)).thenReturn(NOM);
            when(stationSnapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ExamStationSnapshot out = materialiser.materialiseStation(EXAMEN, STATION);

            ArgumentCaptor<ExamStationSnapshot> cap = ArgumentCaptor.forClass(ExamStationSnapshot.class);
            verify(stationSnapshotRepository).save(cap.capture());
            assertThat(cap.getValue().getExamenId()).isEqualTo(EXAMEN);
            assertThat(cap.getValue().getStationId()).isEqualTo(STATION);
            assertThat(cap.getValue().getNom()).isEqualTo(NOM);
            assertThat(out.getNom()).isEqualTo(NOM);
        }

        /**
         * La garantie centrale de l'ADR : ce qui est écrit est définitif, donc un échec amont ne doit
         * RIEN écrire. Figer « Station 5 » ici le rendrait permanent et indétectable.
         */
        @Test
        @DisplayName("n'écrit RIEN si le client échoue — jamais de repli figé")
        void nEcritRienSiLeClientEchoue() {
            when(examServiceClient.getStationNomStrict(STATION))
                    .thenThrow(new BusinessException("exam-service injoignable"));

            assertThatThrownBy(() -> materialiser.materialiseStation(EXAMEN, STATION))
                    .isInstanceOf(BusinessException.class);

            verify(stationSnapshotRepository, never()).save(any());
        }

        @Test
        @DisplayName("course perdue : relit le gagnant au lieu de propager")
        void coursePerdueRelitLeGagnant() {
            ExamStationSnapshot gagnant = ExamStationSnapshot.builder()
                    .examenId(EXAMEN).stationId(STATION).nom(NOM).build();
            when(examServiceClient.getStationNomStrict(STATION)).thenReturn(NOM);
            when(stationSnapshotRepository.save(any()))
                    .thenThrow(new DataIntegrityViolationException("unique violation"));
            when(stationSnapshotRepository.findByStationId(STATION)).thenReturn(Optional.of(gagnant));

            assertThat(materialiser.materialiseStation(EXAMEN, STATION).getNom()).isEqualTo(NOM);
        }

        /**
         * Course signalée mais rien à relire ⇒ ce n'était pas une course. Propager l'erreur d'origine
         * plutôt que de masquer une vraie violation de contrainte.
         */
        @Test
        @DisplayName("course sans gagnant lisible : propage l'erreur d'origine")
        void courseSansGagnantPropage() {
            when(examServiceClient.getStationNomStrict(STATION)).thenReturn(NOM);
            when(stationSnapshotRepository.save(any()))
                    .thenThrow(new DataIntegrityViolationException("unique violation"));
            when(stationSnapshotRepository.findByStationId(STATION)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> materialiser.materialiseStation(EXAMEN, STATION))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("materialiseItems()")
    class Items {

        @Test
        @DisplayName("fige un critère par item notable, avec type et pondération")
        void figeLesItemsNotables() {
            when(examServiceClient.getItemInfosForGrilleStrict(GRILLE)).thenReturn(Map.of(
                    17L, new ExamServiceClient.ItemInfo(17L, 5d, "BINAIRE"),
                    19L, new ExamServiceClient.ItemInfo(19L, 6d, "NUMERIQUE")));
            when(itemSnapshotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

            List<ExamItemSnapshot> out = materialiser.materialiseItems(EXAMEN, GRILLE);

            assertThat(out).hasSize(2);
            assertThat(out).allSatisfy(i -> {
                assertThat(i.getExamenId()).isEqualTo(EXAMEN);
                assertThat(i.getGrilleId()).isEqualTo(GRILLE);
            });
            assertThat(out).extracting(ExamItemSnapshot::getItemId)
                    .containsExactlyInAnyOrder(17L, 19L);
            assertThat(out).filteredOn(i -> i.getItemId() == 17L)
                    .singleElement()
                    .satisfies(i -> {
                        assertThat(i.getType()).isEqualTo("BINAIRE");
                        assertThat(i.getPonderation()).isEqualTo(5d);
                    });
        }

        /**
         * Figer un ensemble VIDE serait le pire résultat possible : le snapshot fait autorité sur ce
         * qui est notable, donc un ensemble vide rendrait TOUT item « inconnu » de façon permanente
         * et bloquerait la notation pour toujours. Échouer fort est la bonne réponse.
         */
        @Test
        @DisplayName("grille sans critère notable : échoue et ne fige PAS un ensemble vide")
        void grilleVideEchoueSansRienFiger() {
            when(examServiceClient.getItemInfosForGrilleStrict(GRILLE)).thenReturn(Map.of());

            assertThatThrownBy(() -> materialiser.materialiseItems(EXAMEN, GRILLE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("aucun critère notable");

            verify(itemSnapshotRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("n'écrit RIEN si le client échoue")
        void nEcritRienSiLeClientEchoue() {
            when(examServiceClient.getItemInfosForGrilleStrict(GRILLE))
                    .thenThrow(new BusinessException("exam-service injoignable"));

            assertThatThrownBy(() -> materialiser.materialiseItems(EXAMEN, GRILLE))
                    .isInstanceOf(BusinessException.class);

            verify(itemSnapshotRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("course perdue : renvoie les lignes du gagnant")
        void coursePerdueRenvoieLExistant() {
            ExamItemSnapshot existant = ExamItemSnapshot.builder()
                    .examenId(EXAMEN).grilleId(GRILLE).itemId(17L).type("BINAIRE").ponderation(5d).build();
            when(examServiceClient.getItemInfosForGrilleStrict(GRILLE)).thenReturn(
                    Map.of(17L, new ExamServiceClient.ItemInfo(17L, 5d, "BINAIRE")));
            when(itemSnapshotRepository.saveAll(any()))
                    .thenThrow(new DataIntegrityViolationException("unique violation"));
            when(itemSnapshotRepository.findByGrilleId(GRILLE)).thenReturn(List.of(existant));

            assertThat(materialiser.materialiseItems(EXAMEN, GRILLE))
                    .singleElement()
                    .satisfies(i -> assertThat(i.getItemId()).isEqualTo(17L));
        }

        @Test
        @DisplayName("course sans gagnant lisible : propage l'erreur d'origine")
        void courseSansGagnantPropage() {
            when(examServiceClient.getItemInfosForGrilleStrict(GRILLE)).thenReturn(
                    Map.of(17L, new ExamServiceClient.ItemInfo(17L, 5d, "BINAIRE")));
            when(itemSnapshotRepository.saveAll(any()))
                    .thenThrow(new DataIntegrityViolationException("unique violation"));
            when(itemSnapshotRepository.findByGrilleId(GRILLE)).thenReturn(List.of());

            assertThatThrownBy(() -> materialiser.materialiseItems(EXAMEN, GRILLE))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("materialiseGrille()")
    class Grille {

        private static final String NOM_GRILLE = "Grille Station 3 — Titrimétrie";

        private com.fasterxml.jackson.databind.JsonNode grilleJson() throws Exception {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    "{\"id\":5,\"nom\":\"" + NOM_GRILLE + "\",\"noteMax\":20.0,"
                            + "\"items\":[{\"id\":1,\"libelle\":\"x\",\"ponderation\":2.0}]}");
        }

        @Test
        @DisplayName("fige la grille renvoyée par la variante STRICTE du client")
        void figeLaGrilleStrict() throws Exception {
            when(examServiceClient.getGrilleStrict(STATION)).thenReturn(grilleJson());
            when(grilleSnapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ExamGrilleSnapshot out = materialiser.materialiseGrille(EXAMEN, STATION);

            assertThat(out.getExamenId()).isEqualTo(EXAMEN);
            assertThat(out.getStationId()).isEqualTo(STATION);
            assertThat(out.getNom()).isEqualTo(NOM_GRILLE);
            assertThat(out.getNoteMax()).isEqualTo(20.0);
            assertThat(out.getItemsJson()).contains("\"id\":1");
        }

        @Test
        @DisplayName("items absent → itemsJson vaut '[]', jamais une chaîne vide illisible")
        void itemsAbsent_devientTableauVide() throws Exception {
            com.fasterxml.jackson.databind.JsonNode data =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree("{\"id\":5,\"nom\":\"Vide\",\"noteMax\":20.0}");
            when(examServiceClient.getGrilleStrict(STATION)).thenReturn(data);
            when(grilleSnapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ExamGrilleSnapshot out = materialiser.materialiseGrille(EXAMEN, STATION);

            assertThat(out.getItemsJson()).isEqualTo("[]");
        }

        @Test
        @DisplayName("n'écrit RIEN si le client échoue")
        void nEcritRienSiLeClientEchoue() {
            when(examServiceClient.getGrilleStrict(STATION))
                    .thenThrow(new BusinessException("exam-service injoignable"));

            assertThatThrownBy(() -> materialiser.materialiseGrille(EXAMEN, STATION))
                    .isInstanceOf(BusinessException.class);
            verify(grilleSnapshotRepository, never()).save(any());
        }

        @Test
        @DisplayName("course perdue : relit le gagnant")
        void coursePerdueRelitLeGagnant() throws Exception {
            ExamGrilleSnapshot gagnant = ExamGrilleSnapshot.builder()
                    .examenId(EXAMEN).stationId(STATION).nom(NOM_GRILLE).build();
            when(examServiceClient.getGrilleStrict(STATION)).thenReturn(grilleJson());
            when(grilleSnapshotRepository.save(any()))
                    .thenThrow(new DataIntegrityViolationException("unique violation"));
            when(grilleSnapshotRepository.findByStationId(STATION)).thenReturn(Optional.of(gagnant));

            assertThat(materialiser.materialiseGrille(EXAMEN, STATION).getNom()).isEqualTo(NOM_GRILLE);
        }

        @Test
        @DisplayName("course sans gagnant lisible : propage l'erreur d'origine")
        void courseSansGagnantPropage() throws Exception {
            when(examServiceClient.getGrilleStrict(STATION)).thenReturn(grilleJson());
            when(grilleSnapshotRepository.save(any()))
                    .thenThrow(new DataIntegrityViolationException("unique violation"));
            when(grilleSnapshotRepository.findByStationId(STATION)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> materialiser.materialiseGrille(EXAMEN, STATION))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    /**
     * Régression du défaut latent HIGH de session 21 : la matérialisation DOIT vivre dans un bean
     * distinct, sinon {@code REQUIRES_NEW} est appliqué via {@code this}, ne traverse pas le proxy
     * Spring et devient inerte — ce qui annulait à la fois le rattrapage de course et la durabilité.
     */
    @Test
    @DisplayName("les trois écritures portent REQUIRES_NEW (le proxy doit être traversé)")
    void lesEcrituresPortentRequiresNew() throws NoSuchMethodException {
        for (String m : List.of("materialiseStation", "materialiseItems", "materialiseGrille")) {
            var annotation = ExamDefinitionMaterialiser.class
                    .getMethod(m, Long.class, Long.class)
                    .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
            assertThat(annotation)
                    .as("%s doit être @Transactional", m)
                    .isNotNull();
            assertThat(annotation.propagation())
                    .as("%s doit être REQUIRES_NEW — sinon le snapshot est annulé "
                            + "avec la transaction métier appelante", m)
                    .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
        }
    }
}
