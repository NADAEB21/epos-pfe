package tn.epos.scoring_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ConflictException;
import tn.epos.scoring_service.client.ExamServiceClient;
import tn.epos.scoring_service.config.EvaluateurScopeChecker;
import tn.epos.scoring_service.dto.BaremeDeliberationDTO;
import tn.epos.scoring_service.dto.BaremeDeliberationRequest;
import tn.epos.scoring_service.dto.BaremeDeliberationRequest.OperationRequest;
import tn.epos.scoring_service.entities.BaremeDeliberation;
import tn.epos.scoring_service.entities.BaremeDeliberationOperation;
import tn.epos.scoring_service.entities.ExamGrilleSnapshot;
import tn.epos.scoring_service.entities.ExamItemSnapshot;
import tn.epos.scoring_service.entities.TypeOperationBareme;
import tn.epos.scoring_service.repositories.ExamGrilleSnapshotRepository;
import tn.epos.scoring_service.repositories.ExamItemSnapshotRepository;
import tn.epos.scoring_service.repositories.IBaremeDeliberationOperationRepository;
import tn.epos.scoring_service.repositories.IBaremeDeliberationRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-0030 (#361) — gardes et versionnement du barème de délibération, forme de
 * {@code NotationReajustementServiceTest} : {@code MatiereAccessGuard} mocké
 * permissif (le périmètre de matière a ses propres tests), l'ENGINE réel (le
 * calcul du maximum d'une cible fait partie du comportement validé — le stuber
 * ne testerait rien).
 *
 * <p>Chaque REFUS vérifie aussi que RIEN n'est écrit ({@code save} jamais
 * appelé) — le protocole sentinelle du dépôt, côté unitaire.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BaremeDeliberationService — gardes, validation, versions (ADR-0030, #361)")
class BaremeDeliberationServiceTest {

    @Mock
    private IBaremeDeliberationRepository baremeRepository;
    @Mock
    private IBaremeDeliberationOperationRepository operationRepository;
    @Mock
    private ExamItemSnapshotRepository itemSnapshotRepository;
    @Mock
    private ExamGrilleSnapshotRepository grilleSnapshotRepository;
    @Mock
    private EvaluateurScopeChecker scopeChecker;
    @Mock
    private MatiereAccessGuard matiereAccessGuard;
    @Mock
    private ExamServiceClient examServiceClient;

    private BaremeDeliberationService service;

    private static final long EXAMEN = 77L;
    private static final String ITEMS_JSON = """
            [
              {"id": 1, "type": "NUMERIQUE", "valeurMax": 8.0},
              {"id": 2, "type": "BINAIRE", "valeurMax": null},
              {"id": 4, "type": "NUMERIQUE", "valeurMax": null}
            ]""";

    @BeforeEach
    void setUp() {
        BaremeDeliberationEngine engine = new BaremeDeliberationEngine(
                baremeRepository, operationRepository, itemSnapshotRepository,
                grilleSnapshotRepository, new ObjectMapper());
        service = new BaremeDeliberationService(baremeRepository, operationRepository,
                itemSnapshotRepository, grilleSnapshotRepository, scopeChecker,
                matiereAccessGuard, examServiceClient, engine);

        // Nominal permissif par défaut ; chaque garde se referme dans SON test.
        lenient().when(scopeChecker.getCallerUserId()).thenReturn(5L);
        lenient().when(examServiceClient.getStatutStrict(EXAMEN)).thenReturn("TERMINE");
        lenient().when(grilleSnapshotRepository.findByExamenId(EXAMEN)).thenReturn(List.of(
                ExamGrilleSnapshot.builder().examenId(EXAMEN).stationId(101L).grilleId(201L)
                        .nom("Station").noteMax(20.0).itemsJson(ITEMS_JSON).build()));
        lenient().when(itemSnapshotRepository.findByExamenId(EXAMEN)).thenReturn(List.of(
                item(1L, "NUMERIQUE"), item(2L, "BINAIRE"), item(4L, "NUMERIQUE")));
        lenient().when(baremeRepository.findTopByExamenIdOrderByVersionDesc(EXAMEN))
                .thenReturn(Optional.empty());
        lenient().when(baremeRepository.save(any())).thenAnswer(inv -> {
            BaremeDeliberation b = inv.getArgument(0);
            b.setId(9L);
            return b;
        });
        lenient().when(operationRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static ExamItemSnapshot item(long itemId, String type) {
        return ExamItemSnapshot.builder()
                .examenId(EXAMEN).grilleId(201L).itemId(itemId).type(type).ponderation(5.0)
                .build();
    }

    private static BaremeDeliberationRequest req(OperationRequest... ops) {
        return new BaremeDeliberationRequest("Motif de délibération", List.of(ops));
    }

    private static OperationRequest exclureCritere(long itemId) {
        return new OperationRequest(TypeOperationBareme.EXCLURE_CRITERE, itemId, null, null);
    }

    @Nested
    @DisplayName("Gardes — refus SANS écriture")
    class Gardes {

        @Test
        @DisplayName("Identité introuvable (userId null) → 403, rien écrit")
        void sansIdentite_refuse() {
            when(scopeChecker.getCallerUserId()).thenReturn(null);

            assertThatThrownBy(() -> service.creer(EXAMEN, req()))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Identité");
            verify(baremeRepository, never()).save(any());
        }

        @Test
        @DisplayName("Hors matière → l'AccessDenied du garde #274 remonte, rien écrit")
        void horsMatiere_refuse() {
            org.mockito.Mockito.doThrow(new AccessDeniedException("matière hors périmètre"))
                    .when(matiereAccessGuard).checkExamenAccess(EXAMEN);

            assertThatThrownBy(() -> service.creer(EXAMEN, req()))
                    .isInstanceOf(AccessDeniedException.class);
            verify(baremeRepository, never()).save(any());
            // le périmètre se vérifie AVANT le statut (précédent N6)
            verify(examServiceClient, never()).getStatutStrict(any());
        }

        @Test
        @DisplayName("Examen EN_COURS → 409 nominatif, rien écrit")
        void examenNonClos_refuse() {
            when(examServiceClient.getStatutStrict(EXAMEN)).thenReturn("EN_COURS");

            assertThatThrownBy(() -> service.creer(EXAMEN, req()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("EN_COURS")
                    .hasMessageContaining("clos");
            verify(baremeRepository, never()).save(any());
        }

        @Test
        @DisplayName("exam-service muet → l'échec STRICT remonte (garde fermée), rien écrit")
        void examServiceMuet_refuseFerme() {
            when(examServiceClient.getStatutStrict(EXAMEN))
                    .thenThrow(new BusinessException("exam-service injoignable — statut invérifiable"));

            assertThatThrownBy(() -> service.creer(EXAMEN, req()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("injoignable");
            verify(baremeRepository, never()).save(any());
        }

        @Test
        @DisplayName("ARCHIVE est clos aussi (le verrou institutionnel est différé #236/W12)")
        void archive_estClos() {
            when(examServiceClient.getStatutStrict(EXAMEN)).thenReturn("ARCHIVE");

            BaremeDeliberationDTO dto = service.creer(EXAMEN, req());

            assertThat(dto.version()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Validation des opérations — refus nominatifs, rien écrit")
    class Validation {

        @Test
        @DisplayName("Cible critère inconnue du snapshot → refus")
        void critereInconnu() {
            assertThatThrownBy(() -> service.creer(EXAMEN, req(exclureCritere(999L))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("999")
                    .hasMessageContaining("snapshot");
            verify(baremeRepository, never()).save(any());
        }

        @Test
        @DisplayName("Cible station inconnue du snapshot → refus")
        void stationInconnue() {
            assertThatThrownBy(() -> service.creer(EXAMEN, req(new OperationRequest(
                    TypeOperationBareme.EXCLURE_STATION, null, 999L, null))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("999");
            verify(baremeRepository, never()).save(any());
        }

        @Test
        @DisplayName("NUMERIQUE sans valeur_max au snapshot → cible refusée (dénominateur impossible)")
        void numeriqueSansValeurMax() {
            assertThatThrownBy(() -> service.creer(EXAMEN, req(exclureCritere(4L))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("valeur");
            verify(baremeRepository, never()).save(any());
        }

        @Test
        @DisplayName("REPONDERER sans nouvelleEchelle → refus")
        void reponderationSansEchelle() {
            assertThatThrownBy(() -> service.creer(EXAMEN, req(new OperationRequest(
                    TypeOperationBareme.REPONDERER, 1L, null, null))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("nouvelleEchelle");
            verify(baremeRepository, never()).save(any());
        }

        @Test
        @DisplayName("REPONDERER avec les DEUX cibles → refus (exactement une)")
        void reponderationDeuxCibles() {
            assertThatThrownBy(() -> service.creer(EXAMEN, req(new OperationRequest(
                    TypeOperationBareme.REPONDERER, 1L, 101L, 4.0))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("exactement un");
            verify(baremeRepository, never()).save(any());
        }

        @Test
        @DisplayName("nouvelleEchelle sur une EXCLUSION → refus (intention ambiguë)")
        void echelleSurExclusion() {
            assertThatThrownBy(() -> service.creer(EXAMEN, req(new OperationRequest(
                    TypeOperationBareme.EXCLURE_CRITERE, 1L, null, 4.0))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("REPONDERER");
            verify(baremeRepository, never()).save(any());
        }

        @Test
        @DisplayName("Même critère ciblé deux fois → refus")
        void doubleCible() {
            assertThatThrownBy(() -> service.creer(EXAMEN,
                    req(exclureCritere(1L), exclureCritere(1L))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("une seule par cible");
            verify(baremeRepository, never()).save(any());
        }

        @Test
        @DisplayName("Station ciblée + opération critère chez elle → refus (un seul niveau)")
        void stationExclueEtCritere() {
            assertThatThrownBy(() -> service.creer(EXAMEN, req(
                    new OperationRequest(TypeOperationBareme.EXCLURE_STATION, null, 101L, null),
                    exclureCritere(1L))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("ambigu");
            verify(baremeRepository, never()).save(any());
        }

        @Test
        @DisplayName("Critère d'une grille SANS snapshot de barème (station jamais notée) → refus")
        void critereSansGrilleSnapshotee() {
            // grille 999 absente d'exam_grille_snapshot : la station serait
            // irrésoluble à la lecture — l'opération serait un no-op silencieux.
            when(itemSnapshotRepository.findByExamenId(EXAMEN)).thenReturn(List.of(
                    ExamItemSnapshot.builder().examenId(EXAMEN).grilleId(999L)
                            .itemId(7L).type("BINAIRE").ponderation(5.0).build()));

            assertThatThrownBy(() -> service.creer(EXAMEN, req(exclureCritere(7L))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("snapshoté");
            verify(baremeRepository, never()).save(any());
        }

        @Test
        @DisplayName("Examen sans snapshot (pré-V19) → aucune cible définissable, refus")
        void sansSnapshot() {
            when(grilleSnapshotRepository.findByExamenId(EXAMEN)).thenReturn(List.of());
            when(itemSnapshotRepository.findByExamenId(EXAMEN)).thenReturn(List.of());

            assertThatThrownBy(() -> service.creer(EXAMEN, req(exclureCritere(1L))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("V19");
            verify(baremeRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Versionnement — immuable, la dernière fait foi (D3)")
    class Versions {

        @Test
        @DisplayName("Première version = 1, motif + auteur portés, opérations écrites")
        void premiereVersion() {
            BaremeDeliberationDTO dto = service.creer(EXAMEN, req(exclureCritere(1L)));

            assertThat(dto.version()).isEqualTo(1);
            assertThat(dto.creePar()).isEqualTo(5L);
            assertThat(dto.motif()).isEqualTo("Motif de délibération");
            assertThat(dto.operations()).hasSize(1);
            assertThat(dto.operations().get(0).cibleItemId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Version suivante = max + 1 ; la VIDE (retour à l'origine) est valide")
        void versionSuivante_videValide() {
            when(baremeRepository.findTopByExamenIdOrderByVersionDesc(EXAMEN))
                    .thenReturn(Optional.of(BaremeDeliberation.builder()
                            .id(8L).examenId(EXAMEN).version(3).motif("m").creePar(5L).build()));
            when(operationRepository.findByBaremeId(8L))
                    .thenReturn(List.of(BaremeDeliberationOperation.builder()
                            .baremeId(8L).type(TypeOperationBareme.EXCLURE_CRITERE)
                            .cibleItemId(1L).build()));

            BaremeDeliberationDTO dto = service.creer(EXAMEN, req());

            assertThat(dto.version()).isEqualTo(4);
            assertThat(dto.operations()).isEmpty();
        }

        @Test
        @DisplayName("Opérations identiques à la version courante → 409, rien écrit (D5)")
        void doubleApplication_refusee() {
            when(baremeRepository.findTopByExamenIdOrderByVersionDesc(EXAMEN))
                    .thenReturn(Optional.of(BaremeDeliberation.builder()
                            .id(8L).examenId(EXAMEN).version(2).motif("m").creePar(5L).build()));
            when(operationRepository.findByBaremeId(8L))
                    .thenReturn(List.of(BaremeDeliberationOperation.builder()
                            .baremeId(8L).type(TypeOperationBareme.EXCLURE_CRITERE)
                            .cibleItemId(1L).build()));

            assertThatThrownBy(() -> service.creer(EXAMEN, req(exclureCritere(1L))))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("version 2");
            verify(baremeRepository, never()).save(any());
        }

        @Test
        @DisplayName("Historique : versions desc, opérations groupées par version")
        void historique() {
            BaremeDeliberation v2 = BaremeDeliberation.builder()
                    .id(9L).examenId(EXAMEN).version(2).motif("retour").creePar(5L).build();
            BaremeDeliberation v1 = BaremeDeliberation.builder()
                    .id(8L).examenId(EXAMEN).version(1).motif("exclusion").creePar(5L).build();
            when(baremeRepository.findByExamenIdOrderByVersionDesc(EXAMEN))
                    .thenReturn(List.of(v2, v1));
            when(operationRepository.findByBaremeIdIn(List.of(9L, 8L)))
                    .thenReturn(List.of(BaremeDeliberationOperation.builder()
                            .baremeId(8L).type(TypeOperationBareme.EXCLURE_CRITERE)
                            .cibleItemId(1L).build()));

            List<BaremeDeliberationDTO> histo = service.historique(EXAMEN);

            assertThat(histo).extracting(BaremeDeliberationDTO::version).containsExactly(2, 1);
            assertThat(histo.get(0).operations()).isEmpty();
            assertThat(histo.get(1).operations()).hasSize(1);
        }
    }
}
