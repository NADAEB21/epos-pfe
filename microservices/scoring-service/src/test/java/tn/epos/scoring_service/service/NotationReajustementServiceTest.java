package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.config.EvaluateurScopeChecker;
import tn.epos.scoring_service.dto.NotationAdjustmentDTO;
import tn.epos.scoring_service.dto.ReajustementRequest;
import tn.epos.scoring_service.entities.ExamItemSnapshot;
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.entities.NotationAdjustment;
import tn.epos.scoring_service.entities.NotationItem;
import tn.epos.scoring_service.repositories.INotationAdjustmentRepository;
import tn.epos.scoring_service.repositories.INotationItemRepository;
import tn.epos.scoring_service.repositories.INotationRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotationReajustementService — canal de réajustement audité (ADR-0013 Part 2, #23/#135)")
class NotationReajustementServiceTest {

    private static final Long RESP_USER_ID = 77L;

    @Mock private INotationRepository notationRepository;
    @Mock private INotationItemRepository notationItemRepository;
    @Mock private INotationAdjustmentRepository adjustmentRepository;
    @Mock private EvaluateurScopeChecker scopeChecker;
    @Mock private ExamDefinitionSnapshotService examDefinitionSnapshot;

    @InjectMocks
    private NotationReajustementService service;

    private Notation locked;

    @BeforeEach
    void setUp() {
        // Une notation VERROUILLÉE — c'est tout l'objet du canal : y toucher sans
        // jamais la déverrouiller.
        locked = new Notation();
        locked.setId(5L);
        locked.setScore_final(15.0f);
        locked.setGrilleId(11L);
        locked.setVerouillee(true);

        // ADR-0015 — weigh() garde l'arithmétique RÉELLE (déléguée à ExamItemSnapshot) :
        // c'est précisément le calcul dont les deux copies avaient divergé, le stubber
        // reviendrait à ne plus le tester.
        lenient().when(examDefinitionSnapshot.weigh(anyMap(), anyLong(), any()))
                .thenAnswer(inv -> {
                    Map<Long, ExamItemSnapshot> def = inv.getArgument(0);
                    ExamItemSnapshot it = def.get((Long) inv.getArgument(1));
                    if (it == null) throw new tn.epos.common.exception.BusinessException("hors snapshot");
                    return it.weigh(inv.getArgument(2));
                });
    }

    /** ADR-0015 — définition figée d'un critère notable. */
    private static Map<Long, ExamItemSnapshot> definition(long itemId, double pond, String type) {
        return Map.of(itemId, ExamItemSnapshot.builder()
                .examenId(99L).grilleId(11L).itemId(itemId).ponderation(pond).type(type).build());
    }

    @Nested
    @DisplayName("reajuster() — critère (itemId présent)")
    class ItemLevel {

        @Test
        @DisplayName("Applique la nouvelle valeur, recalcule le score et trace l'ajustement — la notation reste verrouillée")
        void reajuste_critere_appliqueEtAudite() {
            when(scopeChecker.getCallerUserId()).thenReturn(RESP_USER_ID);
            when(notationRepository.findById(5L)).thenReturn(Optional.of(locked));

            NotationItem item = new NotationItem();
            item.setId(1L);
            item.setItemId(100L);
            item.setValeur(10.0f);
            item.setNotation(locked);
            when(notationItemRepository.findByNotationIdAndItemId(5L, 100L)).thenReturn(Optional.of(item));
            when(notationItemRepository.save(any(NotationItem.class))).thenAnswer(inv -> inv.getArgument(0));

            // Recompute : un seul item, type NUMERIQUE (non pondéré) → score = valeur.
            when(notationItemRepository.findByNotationId(5L)).thenReturn(List.of(item));
            when(examDefinitionSnapshot.resolveItems(any(), eq(11L)))
                    .thenReturn(definition(100L, 1.0, "NUMERIQUE"));
            when(adjustmentRepository.save(any(NotationAdjustment.class))).thenAnswer(inv -> inv.getArgument(0));

            ReajustementRequest req = new ReajustementRequest(100L, 8.0f, "Réclamation étudiant : erreur de saisie");

            NotationAdjustment result = service.reajuster(5L, req);

            // La valeur du critère a bien changé.
            assertThat(item.getValeur()).isEqualTo(8.0f);
            // Le score total a été recalculé.
            assertThat(locked.getScore_final()).isEqualTo(8.0f);
            // La notation N'A PAS été déverrouillée.
            assertThat(locked.getVerouillee()).isTrue();

            // La ligne d'audit capture l'ancien → nouveau (valeur + score) + qui/pourquoi.
            ArgumentCaptor<NotationAdjustment> captor = ArgumentCaptor.forClass(NotationAdjustment.class);
            verify(adjustmentRepository).save(captor.capture());
            NotationAdjustment adj = captor.getValue();
            assertThat(adj.getNotationId()).isEqualTo(5L);
            assertThat(adj.getItemId()).isEqualTo(100L);
            assertThat(adj.getAncienneValeur()).isEqualTo(10.0f);
            assertThat(adj.getNouvelleValeur()).isEqualTo(8.0f);
            assertThat(adj.getAncienScore()).isEqualTo(15.0f);
            assertThat(adj.getNouveauScore()).isEqualTo(8.0f);
            assertThat(adj.getMotif()).isEqualTo("Réclamation étudiant : erreur de saisie");
            assertThat(adj.getAdjustedByUserId()).isEqualTo(RESP_USER_ID);
            assertThat(result).isSameAs(adj);
        }

        @Test
        @DisplayName("Recalcul PONDÉRÉ : un item BINAIRE compte valeur × pondération (parité avec la saisie évaluateur)")
        void reajuste_critereBinaire_recalculePondere() {
            when(scopeChecker.getCallerUserId()).thenReturn(RESP_USER_ID);
            when(notationRepository.findById(5L)).thenReturn(Optional.of(locked));

            NotationItem bin = new NotationItem();
            bin.setItemId(200L);
            bin.setValeur(0.0f);
            bin.setNotation(locked);
            when(notationItemRepository.findByNotationIdAndItemId(5L, 200L)).thenReturn(Optional.of(bin));
            when(notationItemRepository.save(any(NotationItem.class))).thenAnswer(inv -> inv.getArgument(0));
            when(notationItemRepository.findByNotationId(5L)).thenReturn(List.of(bin));
            when(examDefinitionSnapshot.resolveItems(any(), eq(11L)))
                    .thenReturn(definition(200L, 5.0, "BINAIRE"));
            when(adjustmentRepository.save(any(NotationAdjustment.class))).thenAnswer(inv -> inv.getArgument(0));

            service.reajuster(5L, new ReajustementRequest(200L, 1.0f, "Réclamation : point acquis"));

            // valeur 1 × pondération 5 = 5.0
            assertThat(bin.getValeur()).isEqualTo(1.0f);
            assertThat(locked.getScore_final()).isEqualTo(5.0f);
        }

        @Test
        @DisplayName("Critère introuvable → ResourceNotFoundException, aucun audit écrit")
        void reajuste_critereIntrouvable_leve() {
            when(scopeChecker.getCallerUserId()).thenReturn(RESP_USER_ID);
            when(notationRepository.findById(5L)).thenReturn(Optional.of(locked));
            when(notationItemRepository.findByNotationIdAndItemId(5L, 999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reajuster(5L, new ReajustementRequest(999L, 8.0f, "x")))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("999");

            verify(adjustmentRepository, never()).save(any());
            assertThat(locked.getScore_final()).isEqualTo(15.0f); // inchangé
        }
    }

    @Nested
    @DisplayName("reajuster() — total (itemId absent)")
    class TotalLevel {

        @Test
        @DisplayName("Surcharge directe du score_final, trace l'ajustement, aucun accès aux items/exam-service")
        void reajuste_total_appliqueEtAudite() {
            when(scopeChecker.getCallerUserId()).thenReturn(RESP_USER_ID);
            when(notationRepository.findById(5L)).thenReturn(Optional.of(locked));
            when(adjustmentRepository.save(any(NotationAdjustment.class))).thenAnswer(inv -> inv.getArgument(0));

            NotationAdjustment adj = service.reajuster(5L, new ReajustementRequest(null, 12.5f, "Décision jury réclamation"));

            assertThat(locked.getScore_final()).isEqualTo(12.5f);
            assertThat(locked.getVerouillee()).isTrue();
            assertThat(adj.getItemId()).isNull();
            assertThat(adj.getAncienneValeur()).isNull();
            assertThat(adj.getNouvelleValeur()).isNull();
            assertThat(adj.getAncienScore()).isEqualTo(15.0f);
            assertThat(adj.getNouveauScore()).isEqualTo(12.5f);
            assertThat(adj.getAdjustedByUserId()).isEqualTo(RESP_USER_ID);

            verify(notationItemRepository, never()).findByNotationIdAndItemId(any(), any());
            verify(examDefinitionSnapshot, never()).resolveItems(any(), any());
            verify(notationRepository).save(locked);
        }
    }

    @Nested
    @DisplayName("Garde-fous")
    class Guards {

        @Test
        @DisplayName("Identité appelant introuvable → AccessDeniedException, aucune écriture")
        void callerInconnu_refuse() {
            when(scopeChecker.getCallerUserId()).thenReturn(null);

            assertThatThrownBy(() -> service.reajuster(5L, new ReajustementRequest(null, 12f, "x")))
                    .isInstanceOf(AccessDeniedException.class);

            verify(notationRepository, never()).findById(any());
            verify(adjustmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("Notation introuvable → ResourceNotFoundException, aucun audit")
        void notationIntrouvable_leve() {
            when(scopeChecker.getCallerUserId()).thenReturn(RESP_USER_ID);
            when(notationRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reajuster(99L, new ReajustementRequest(null, 12f, "x")))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("99");

            verify(adjustmentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("historique()")
    class Historique {

        @Test
        @DisplayName("Renvoie les ajustements mappés en DTO, du plus récent au plus ancien")
        void historique_mappeDTO() {
            NotationAdjustment a = NotationAdjustment.builder()
                    .id(1L).notationId(5L).itemId(100L)
                    .ancienneValeur(10f).nouvelleValeur(8f)
                    .ancienScore(15f).nouveauScore(8f)
                    .motif("Réclamation").adjustedByUserId(RESP_USER_ID)
                    .adjustedAt(LocalDateTime.now())
                    .build();
            when(adjustmentRepository.findByNotationIdOrderByAdjustedAtDesc(5L)).thenReturn(List.of(a));

            List<NotationAdjustmentDTO> result = service.historique(5L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).notationId()).isEqualTo(5L);
            assertThat(result.get(0).motif()).isEqualTo("Réclamation");
            assertThat(result.get(0).nouveauScore()).isEqualTo(8f);
        }
    }
}
