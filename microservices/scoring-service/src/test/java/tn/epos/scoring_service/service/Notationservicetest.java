package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.scoring_service.config.EvaluateurScopeChecker;
import tn.epos.scoring_service.dto.ExamenResultDTO;
import tn.epos.scoring_service.entities.Etudiant;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationAssignment;
import tn.epos.scoring_service.dto.StationGrilleSnapshotDTO;
import tn.epos.scoring_service.entities.ExamGrilleSnapshot;
import tn.epos.scoring_service.repositories.ExamGrilleSnapshotRepository;
import tn.epos.scoring_service.repositories.INotationItemRepository;
import tn.epos.scoring_service.repositories.INotationRepository;
import tn.epos.scoring_service.repositories.IRotationAssignmentRepository;
import tn.epos.scoring_service.entities.ExamItemSnapshot;
import tn.epos.scoring_service.entities.NotationItem;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotationService - Tests unitaires")
class NotationServiceTest {

    @Mock
    private INotationRepository repository;

    @Mock
    private IRotationAssignmentRepository assignmentRepository;

    @Mock
    private EvaluateurScopeChecker scopeChecker;

    /**
     * #274 — mocké et permissif par défaut : le périmètre de matière a ses propres tests
     * ({@code MatiereScopeCheckerTest}, {@code MatiereAccessGuardTest}). Ici on teste le
     * périmètre ÉVALUATEUR, qui est une autre garde.
     */
    @Mock
    private MatiereAccessGuard matiereAccessGuard;

    @Mock
    private INotationItemRepository notationItemRepository;

    @Mock
    private ExamDefinitionSnapshotService examDefinitionSnapshot;

    // #355 — barèmes snapshotés de l'écran de délibération.
    @Mock
    private ExamGrilleSnapshotRepository grilleSnapshotRepository;

    // #361 — permissif : le recalcul délibéré a ses propres tests
    // (BaremeDeliberationEngineTest) ; sans snapshot stubé il n'est jamais appelé.
    @Mock
    private BaremeDeliberationEngine baremeDeliberationEngine;

    // Réel (pas un mock) : le parsing de items_json fait partie du comportement testé.
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private NotationService notationService;

    private Notation notation;

    @BeforeEach
    void setUp() {
        notation = new Notation();
        notation.setId(1L);
        notation.setScore_final(17.5f);
        notation.setTemps_additionnel(0);
        notation.setIs_synced(false);
        notation.setVerouillee(false);
        notation.setStationId(7L);
        notation.setGrilleId(11L);
        // timestamp géré par @PrePersist — pas de setter manuel nécessaire

        // Par défaut l'appelant LIT hors périmètre (SUPER_ADMIN / RESPONSABLE) : les tests
        // métier existants ne testent pas le périmètre évaluateur, couvert séparément par la
        // classe Nested "Scope évaluateur (#85, #91)" et par EvaluateurScopeCheckerTest.
        // lenient() car certains tests (findById, findByAssignment) ne consultent pas le checker.
        //
        // #274 — c'est bien `peutLireHorsPerimetre` et non le défunt `isUnrestricted` : les
        // écritures ne consultent plus le même booléen. Ce stub ne couvre donc QUE les filtres
        // de liste ; sur les chemins d'écriture, `checkOwnership` est mocké et ne lève rien,
        // ce que les tests de refus expriment par un `doThrow(...)` explicite.
        lenient().when(scopeChecker.peutLireHorsPerimetre()).thenReturn(true);
    }

    // ─── shared helpers ──────────────────────────────────────────────────────
    private Notation notationComplete(long evaluateurId, long examenId, long grilleId, boolean verrouillee) {
        Etudiant e = new Etudiant();
        e.setId(1L); e.setNom("Dupont"); e.setPrenom("Alice");

        ExamenParticipation p = new ExamenParticipation();
        p.setId(10L);
        p.setExamen_id(examenId);
        p.setEtudiant(e);

        Rotation rotation = new Rotation();
        rotation.setEvaluateurId(evaluateurId);

        RotationAssignment assignment = new RotationAssignment();
        assignment.setRotation(rotation);
        assignment.setParticipation(p);

        Notation n = new Notation();
        n.setId(1L);
        n.setVerouillee(verrouillee);
        n.setGrilleId(grilleId);
        n.setAssignment(assignment);
        return n;
    }

    private Map<Long, ExamItemSnapshot> definition(long itemId, double ponderation, String type) {
        return Map.of(itemId, ExamItemSnapshot.builder()
                .examenId(99L).grilleId(11L).itemId(itemId)
                .ponderation(ponderation).type(type)
                .build());
    }

    private NotationItem itemSaisi(Long itemId, float valeur) {
        NotationItem ni = new NotationItem();
        ni.setItemId(itemId);
        ni.setValeur(valeur);
        return ni;
    }


    @Nested
    @DisplayName("findAll()")
    class FindAll {

        @Test
        @DisplayName("Doit retourner toutes les notations")
        void findAll_devraitRetournerListe() {
            when(repository.findAll()).thenReturn(List.of(notation));

            List<Notation> result = notationService.findAll();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getScore_final()).isEqualTo(17.5f);
            assertThat(result.get(0).getVerouillee()).isFalse();
            verify(repository, times(1)).findAll();
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucune notation")
        void findAll_devraitRetournerListeVide() {
            when(repository.findAll()).thenReturn(List.of());

            List<Notation> result = notationService.findAll();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findById()")
    class FindById {

        @Test
        @DisplayName("Doit retourner la notation si trouvée")
        void findById_devraitRetournerNotation() {
            when(repository.findById(1L)).thenReturn(Optional.of(notation));

            Optional<Notation> result = notationService.findById(1L);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Doit retourner Optional vide si introuvable")
        void findById_devraitRetournerVideSiIntrouvable() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            Optional<Notation> result = notationService.findById(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByAssignment()")
    class FindByAssignment {

        @Test
        @DisplayName("Doit retourner la notation liée à un assignment")
        void findByAssignment_devraitRetournerNotation() {
            when(repository.findByAssignmentId(42L)).thenReturn(Optional.of(notation));

            Optional<Notation> result = notationService.findByAssignment(42L);

            assertThat(result).isPresent();
            assertThat(result.get().getScore_final()).isEqualTo(17.5f);
        }

        @Test
        @DisplayName("Doit retourner Optional vide si aucune notation pour cet assignment")
        void findByAssignment_devraitRetournerVideSiAucune() {
            when(repository.findByAssignmentId(99L)).thenReturn(Optional.empty());

            Optional<Notation> result = notationService.findByAssignment(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByStation()")
    class FindByStation {

        @Test
        @DisplayName("Doit retourner les notations d'une station donnée")
        void findByStation_devraitRetournerNotationsDeLaStation() {
            when(repository.findByStationId(7L)).thenReturn(List.of(notation));

            List<Notation> result = notationService.findByStation(7L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStationId()).isEqualTo(7L);
            verify(repository, times(1)).findByStationId(7L);
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucune notation pour cette station")
        void findByStation_devraitRetournerListeVide() {
            when(repository.findByStationId(99L)).thenReturn(List.of());

            List<Notation> result = notationService.findByStation(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByGrille()")
    class FindByGrille {

        @Test
        @DisplayName("Doit retourner les notations d'une grille donnée")
        void findByGrille_devraitRetournerNotationsDeLaGrille() {
            when(repository.findByGrilleId(11L)).thenReturn(List.of(notation));

            List<Notation> result = notationService.findByGrille(11L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getGrilleId()).isEqualTo(11L);
            verify(repository, times(1)).findByGrilleId(11L);
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucune notation pour cette grille")
        void findByGrille_devraitRetournerListeVide() {
            when(repository.findByGrilleId(99L)).thenReturn(List.of());

            List<Notation> result = notationService.findByGrille(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("save()")
    class Save {

        @Test
        @DisplayName("Doit sauvegarder et retourner la notation (sans assignment, appelant non contraint)")
        void save_devraitSauvegarder() {
            when(repository.save(any(Notation.class))).thenReturn(notation);

            Notation result = notationService.save(notation, null);

            assertThat(result).isNotNull();
            assertThat(result.getScore_final()).isEqualTo(17.5f);
            assertThat(result.getIs_synced()).isFalse();
            verify(scopeChecker).checkOwnership(null);
            verify(repository, times(1)).save(any(Notation.class));
        }

        @Test
        @DisplayName("Avec assignmentId : lie l'assignment et vérifie le périmètre via Rotation.evaluateurId")
        void save_avecAssignment_devraitLierEtVerifierPerimetre() {
            Rotation rotation = new Rotation();
            rotation.setEvaluateurId(42L);
            RotationAssignment assignment = new RotationAssignment();
            assignment.setId(5L);
            assignment.setRotation(rotation);
            when(assignmentRepository.findById(5L)).thenReturn(Optional.of(assignment));
            when(repository.save(any(Notation.class))).thenAnswer(inv -> inv.getArgument(0));

            Notation result = notationService.save(notation, 5L);

            assertThat(result.getAssignment()).isSameAs(assignment);
            verify(scopeChecker).checkOwnership(42L);
            verify(repository).save(notation);
        }

        @Test
        @DisplayName("assignmentId introuvable -> ResourceNotFoundException, pas de save")
        void save_assignmentIntrouvable_devraitLever() {
            when(assignmentRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> notationService.save(notation, 99L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("99");
            verify(repository, never()).save(any(Notation.class));
        }
    }

    @Nested
    @DisplayName("delete()")
    class Delete {

        /**
         * #332 — LA CAUSE (prouvée en direct, paire discriminante) : l'assignment managé
         * re-cascade PERSIST sur la notation retirée (RotationAssignment.notation est un
         * @OneToOne inverse cascade=ALL) et Hibernate dé-programme la suppression. Le lien
         * inverse doit être rompu AVANT delete(). Ce test fige l'ordre : unlink puis delete.
         * (Un mock ne peut pas reproduire la résurrection elle-même — elle vit dans le flush
         * Hibernate ; la preuve d'effet réel est le test en direct du 2026-08-14.)
         */
        @Test
        @DisplayName("#332 : le lien inverse assignment→notation est rompu AVANT le delete (anti-résurrection)")
        void delete_rompLeLienInverseAvantDelete() {
            RotationAssignment assignment = new RotationAssignment();
            Notation n = new Notation();
            n.setId(1L);
            n.setAssignment(assignment);
            assignment.setNotation(n);

            when(repository.findById(1L)).thenReturn(Optional.of(n));
            when(repository.existsById(1L)).thenReturn(false);

            notationService.delete(1L);

            assertThat(assignment.getNotation()).isNull();
            verify(repository).delete(n);
        }

        @Test
        @DisplayName("Notation trouvée + réellement absente après flush → suppression confirmée")
        void delete_devraitSupprimerEtVerifierLAbsence() {
            when(repository.findById(1L)).thenReturn(Optional.of(notation));
            // Répond à la relecture post-flush : la ligne n'existe plus (cas nominal).
            when(repository.existsById(1L)).thenReturn(false);

            notationService.delete(1L);

            verify(repository).delete(notation);
            verify(repository).flush();
            verify(repository).existsById(1L);
        }

        @Test
        @DisplayName("Notation introuvable → ResourceNotFoundException, aucun appel delete()")
        void delete_notationIntrouvable_devraitLever() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> notationService.delete(99L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("99");

            verify(repository, never()).delete(any(Notation.class));
            verify(repository, never()).flush();
            verify(repository, never()).existsById(any());
        }

        /**
         * #332 — LE test sentinelle demandé par le ticket : « supprime PUIS relit ». Reproduit
         * exactement le symptôme live (delete() exécuté, flush() exécuté, mais la ligne survit)
         * et vérifie que ce cas précis lève désormais une erreur HONNÊTE au lieu de répondre 200.
         */
        @Test
        @DisplayName("Ligne encore présente après delete()+flush() (répro live #332) → erreur honnête, pas de 200 mensonger")
        void delete_ligneEncorePresenteApresFlush_devraitLeverErreurHonnete() {
            when(repository.findById(1L)).thenReturn(Optional.of(notation));
            // Reproduit le bug observé en direct : la relecture post-flush trouve TOUJOURS la ligne.
            when(repository.existsById(1L)).thenReturn(true);

            assertThatThrownBy(() -> notationService.delete(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("échoué")
                    .hasMessageContaining("1");

            // delete() et flush() ont bien été tentés — on vérifie l'EFFET, pas seulement l'appel.
            verify(repository).delete(notation);
            verify(repository).flush();
        }
    }

    @Nested
    @DisplayName("update()")
    class Update {

        @Test
        @DisplayName("Doit mettre à jour la notation si non verrouillée")
        void update_devraitMettreAJourSiNonVerrouillee() {
            Notation details = new Notation();
            details.setScore_final(20.0f);
            details.setIs_synced(true);
            details.setVerouillee(false);
            details.setTemps_additionnel(5);
            details.setStationId(8L);
            details.setGrilleId(12L);

            when(repository.findById(1L)).thenReturn(Optional.of(notation));
            when(repository.save(any(Notation.class))).thenAnswer(inv -> inv.getArgument(0));

            Notation result = notationService.update(1L, details);

            assertThat(result.getScore_final()).isEqualTo(20.0f);
            assertThat(result.getIs_synced()).isTrue();
            assertThat(result.getTemps_additionnel()).isEqualTo(5);
            assertThat(result.getStationId()).isEqualTo(8L);
            assertThat(result.getGrilleId()).isEqualTo(12L);
            verify(repository).save(any(Notation.class));
        }

        @Test
        @DisplayName("Doit lever RuntimeException si la notation est verrouillée")
        void update_devraitLeverExceptionSiVerrouillee() {
            notation.setVerouillee(true);
            when(repository.findById(1L)).thenReturn(Optional.of(notation));

            assertThatThrownBy(() -> notationService.update(1L, new Notation()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("verrouillée");
        }

        @Test
        @DisplayName("Doit lever RuntimeException si notation introuvable")
        void update_devraitLeverExceptionSiIntrouvable() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> notationService.update(99L, new Notation()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("99");
        }

        @Test
        @DisplayName("#215 : un PUT partiel (sans stationId/grilleId) préserve les colonnes NOT NULL (pas de 500)")
        void update_putPartiel_preserveStationEtGrille() {
            Notation details = new Notation();
            details.setScore_final(11.0f);
            details.setIs_synced(true);
            details.setTemps_additionnel(0);
            // stationId / grilleId / verouillee NON fournis (comme le contrôleur sur PUT)

            when(repository.findById(1L)).thenReturn(Optional.of(notation));
            when(repository.save(any(Notation.class))).thenAnswer(inv -> inv.getArgument(0));

            Notation result = notationService.update(1L, details);

            assertThat(result.getScore_final()).isEqualTo(11.0f);
            assertThat(result.getStationId()).isEqualTo(7L);           // préservé (NOT NULL)
            assertThat(result.getGrilleId()).isEqualTo(11L);           // préservé (NOT NULL)
            assertThat(result.getVerouillee()).isFalse();              // préservé
        }

        @Test
        @DisplayName("#331 : verouillee=true dans le payload est IGNORÉ — le PUT ne verrouille jamais")
        void update_nePermetPlusDeVerrouillerViaLePayload() {
            Notation details = new Notation();
            details.setScore_final(15.0f);
            details.setVerouillee(true); // ← tentative de contournement

            when(repository.findById(1L)).thenReturn(Optional.of(notation)); // verouillee=false initial
            when(repository.save(any(Notation.class))).thenAnswer(inv -> inv.getArgument(0));

            Notation result = notationService.update(1L, details);

            assertThat(result.getVerouillee()).isFalse();
        }
    }

    @Nested
    @DisplayName("verrouiller()")
    class Verrouiller {

        @Test
        @DisplayName("#331 : grille complète (tous critères saisis) → verrouillage accepté")
        void verrouiller_devraitVerrouillerEtSauvegarder() {
            Notation n = notationComplete(1L, 99L, 11L, false);
            when(repository.findById(1L)).thenReturn(Optional.of(n));
            when(examDefinitionSnapshot.resolveItems(99L, 11L))
                    .thenReturn(definition(5L, 2.0, "BINAIRE"));
            when(notationItemRepository.findByNotationId(1L))
                    .thenReturn(List.of(itemSaisi(5L, 1f)));
            when(repository.save(any(Notation.class))).thenAnswer(inv -> inv.getArgument(0));

            Notation result = notationService.verrouiller(1L);

            assertThat(result.getVerouillee()).isTrue();
            verify(repository).save(n);
        }

        @Test
        @DisplayName("Un second appel sur une notation déjà verrouillée est idempotent (no-op, rien réécrit)")
        void verrouiller_devraitResterVerrouilleeSiDejaVerrouillee() {
            notation.setVerouillee(true);
            when(repository.findById(1L)).thenReturn(Optional.of(notation));

            Notation result = notationService.verrouiller(1L);

            assertThat(result.getVerouillee()).isTrue();
            // Idempotent : ni la garde de complétude ni repository.save() ne sont sollicités.
            verify(repository, never()).save(any(Notation.class));
            verifyNoInteractions(examDefinitionSnapshot, notationItemRepository);
        }

        @Test
        @DisplayName("Doit lever RuntimeException si notation introuvable lors du verrouillage")
        void verrouiller_devraitLeverExceptionSiIntrouvable() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> notationService.verrouiller(99L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("99");
        }

        // ── #331 — test sentinelle : coquille 0/N critères + verrouiller() → refus ──────────

        @Test
        @DisplayName("#331 : coquille 0/1 critère saisi → refus, rien n'est écrit")
        void verrouiller_refuse_coquille_sansAucunCritereSaisi() {
            Notation n = notationComplete(1L, 99L, 11L, false);
            when(repository.findById(1L)).thenReturn(Optional.of(n));
            when(examDefinitionSnapshot.resolveItems(99L, 11L))
                    .thenReturn(definition(5L, 2.0, "BINAIRE"));
            when(notationItemRepository.findByNotationId(1L)).thenReturn(List.of());

            assertThatThrownBy(() -> notationService.verrouiller(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("1 critère")
                    .hasMessageContaining("Alice Dupont");

            assertThat(n.getVerouillee()).isFalse();
            verify(repository, never()).save(any(Notation.class));
        }

        @Test
        @DisplayName("#331 : grille à 2 critères, 1 seul saisi → refus")
        void verrouiller_refuse_critereManquant_partiel() {
            Notation n = notationComplete(1L, 99L, 11L, false);
            when(repository.findById(1L)).thenReturn(Optional.of(n));
            Map<Long, ExamItemSnapshot> deuxCriteres = new HashMap<>(definition(5L, 1.0, "NUMERIQUE"));
            deuxCriteres.putAll(definition(6L, 2.0, "BINAIRE"));
            when(examDefinitionSnapshot.resolveItems(99L, 11L)).thenReturn(deuxCriteres);
            when(notationItemRepository.findByNotationId(1L)).thenReturn(List.of(itemSaisi(5L, 1f)));

            assertThatThrownBy(() -> notationService.verrouiller(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("1 critère");

            verify(repository, never()).save(any(Notation.class));
        }

        @Test
        @DisplayName("#331 : notation sans participation résolvable → refus fail-closed")
        void verrouiller_refuse_sansParticipation() {
            RotationAssignment assignment = new RotationAssignment(); // pas de participation
            Notation n = new Notation();
            n.setId(1L); n.setVerouillee(false); n.setGrilleId(11L); n.setAssignment(assignment);
            when(repository.findById(1L)).thenReturn(Optional.of(n));

            assertThatThrownBy(() -> notationService.verrouiller(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("non rattachée à une participation");

            verifyNoInteractions(examDefinitionSnapshot, notationItemRepository);
            verify(repository, never()).save(any(Notation.class));
        }

        @Test
        @DisplayName("#331 : notation sans grilleId → refus fail-closed")
        void verrouiller_refuse_sansGrilleId() {
            Notation n = notationComplete(1L, 99L, 11L, false);
            n.setGrilleId(null);
            when(repository.findById(1L)).thenReturn(Optional.of(n));

            assertThatThrownBy(() -> notationService.verrouiller(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("sans grille associée");

            verifyNoInteractions(examDefinitionSnapshot);
        }
    }

    @Nested
    @DisplayName("getResultatsByExamen() — agrégat par étudiant (#90)")
    class Resultats {

        // Notation rattachée à un étudiant via assignment -> participation -> etudiant.
        private Notation scored(long etudiantId, String nom, String numEch,
                                long participationId, long stationId, float score) {
            Etudiant e = new Etudiant();
            e.setId(etudiantId);
            e.setNom(nom);
            e.setPrenom("P" + etudiantId);
            e.setNumero_inscription("INS-" + etudiantId);

            ExamenParticipation p = new ExamenParticipation();
            p.setId(participationId);
            p.setExamen_id(16L);
            p.setNum_echantillon(numEch);
            p.setEtudiant(e);

            RotationAssignment a = new RotationAssignment();
            a.setParticipation(p);

            Notation n = new Notation();
            n.setStationId(stationId);
            n.setGrilleId(stationId + 100);
            n.setScore_final(score);
            n.setVerouillee(true);
            n.setAssignment(a);
            return n;
        }

        @Test
        @DisplayName("Regroupe par étudiant, somme le total, trie par total décroissant")
        void getResultats_devraitAgregerEtTrier() {
            // Étudiant 1 (participation 100) : deux stations -> total 30
            Notation a1 = scored(1L, "Alpha", "E-01", 100L, 1L, 12f);
            Notation a2 = scored(1L, "Alpha", "E-01", 100L, 2L, 18f);
            // Étudiant 2 (participation 200) : deux stations -> total 25
            Notation b1 = scored(2L, "Beta", "E-02", 200L, 1L, 10f);
            Notation b2 = scored(2L, "Beta", "E-02", 200L, 2L, 15f);
            when(repository.findByExamenIdWithGraph(16L))
                    .thenReturn(List.of(b1, a1, b2, a2));

            List<ExamenResultDTO> results = notationService.getResultatsByExamen(16L);

            assertThat(results).hasSize(2);
            // Trié par total décroissant : Alpha (30) avant Beta (25).
            assertThat(results.get(0).nom()).isEqualTo("Alpha");
            assertThat(results.get(0).totalScore()).isEqualTo(30.0);
            assertThat(results.get(0).stationsNotees()).isEqualTo(2);
            assertThat(results.get(0).numeroInscription()).isEqualTo("INS-1");
            assertThat(results.get(0).numEchantillon()).isEqualTo("E-01");
            // Les stations d'un étudiant sont triées par stationId.
            assertThat(results.get(0).stations()).extracting("stationId")
                    .containsExactly(1L, 2L);
            assertThat(results.get(1).nom()).isEqualTo("Beta");
            assertThat(results.get(1).totalScore()).isEqualTo(25.0);
        }

        @Test
        @DisplayName("Examen sans notation -> liste vide")
        void getResultats_sansNotation_devraitRetournerVide() {
            when(repository.findByExamenIdWithGraph(99L)).thenReturn(List.of());

            assertThat(notationService.getResultatsByExamen(99L)).isEmpty();
        }

        @Test
        @DisplayName("Ignore une notation orpheline (participation null)")
        void getResultats_notationOrpheline_devraitIgnorer() {
            Notation orphan = new Notation();
            orphan.setStationId(1L);
            orphan.setScore_final(10f);
            orphan.setAssignment(new RotationAssignment()); // participation null
            Notation valid = scored(1L, "Alpha", "E-01", 100L, 1L, 14f);
            when(repository.findByExamenIdWithGraph(16L))
                    .thenReturn(List.of(orphan, valid));

            List<ExamenResultDTO> results = notationService.getResultatsByExamen(16L);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).totalScore()).isEqualTo(14.0);
        }
    }

    @Nested
    @DisplayName("Scope évaluateur (#85, #91)")
    class Scope {

        // Notation rattachée à une rotation dont l'évaluateur est 42L.
        private Notation owned(long evaluateurId) {
            Rotation rotation = new Rotation();
            rotation.setEvaluateurId(evaluateurId);
            RotationAssignment assignment = new RotationAssignment();
            assignment.setRotation(rotation);
            Notation n = new Notation();
            n.setId(1L);
            n.setVerouillee(false);
            n.setAssignment(assignment);
            return n;
        }

        @Test
        @DisplayName("verrouiller() propage le 403 quand l'évaluateur n'est pas propriétaire")
        void verrouiller_horsPerimetre_devraitLever403() {
            Notation n = owned(42L);
            when(repository.findById(1L)).thenReturn(Optional.of(n));
            doThrow(new AccessDeniedException("hors perimetre"))
                    .when(scopeChecker).checkOwnership(42L);

            assertThatThrownBy(() -> notationService.verrouiller(1L))
                    .isInstanceOf(AccessDeniedException.class);
            verify(repository, never()).save(any(Notation.class));
        }

        @Test
        @DisplayName("update() propage le 403 quand l'évaluateur n'est pas propriétaire")
        void update_horsPerimetre_devraitLever403() {
            Notation n = owned(42L);
            when(repository.findById(1L)).thenReturn(Optional.of(n));
            doThrow(new AccessDeniedException("hors perimetre"))
                    .when(scopeChecker).checkOwnership(42L);

            assertThatThrownBy(() -> notationService.update(1L, new Notation()))
                    .isInstanceOf(AccessDeniedException.class);
            verify(repository, never()).save(any(Notation.class));
        }

        @Test
        @DisplayName("findAll() filtre les notations hors périmètre pour un évaluateur contraint")
        void findAll_evaluateurContraint_devraitFiltrer() {
            Notation mine = owned(42L);
            Notation other = owned(99L);
            when(scopeChecker.peutLireHorsPerimetre()).thenReturn(false);
            when(repository.findAll()).thenReturn(List.of(mine, other));
            when(scopeChecker.isCaller(42L)).thenReturn(true);
            when(scopeChecker.isCaller(99L)).thenReturn(false);

            List<Notation> result = notationService.findAll();

            assertThat(result).containsExactly(mine);
        }
    }

    // ─── getGrillesSnapshotByExamen() — #355, écran de délibération ──────────

    @Nested
    @DisplayName("getGrillesSnapshotByExamen() — #355, barèmes snapshotés")
    class GetGrillesSnapshotByExamen {

        private ExamGrilleSnapshot snap(Long stationId, String itemsJson) {
            ExamGrilleSnapshot s = new ExamGrilleSnapshot();
            s.setExamenId(77L);
            s.setStationId(stationId);
            s.setGrilleId(stationId + 100);
            s.setNom("Grille S" + stationId);
            s.setNoteMax(20.0);
            s.setItemsJson(itemsJson);
            return s;
        }

        @Test
        @DisplayName("#274 — la garde de matière passe AVANT toute lecture")
        void gardeMatiere_avantLecture() {
            doThrow(new AccessDeniedException("hors matiere"))
                    .when(matiereAccessGuard).checkExamenAccess(77L);

            assertThatThrownBy(() -> notationService.getGrillesSnapshotByExamen(77L))
                    .isInstanceOf(AccessDeniedException.class);
            verify(grilleSnapshotRepository, never()).findByExamenId(any());
        }

        @Test
        @DisplayName("Mappe les snapshots, items parsés tels quels, triés par stationId")
        void mappeEtTrieParStation() {
            when(grilleSnapshotRepository.findByExamenId(77L)).thenReturn(List.of(
                    snap(102L, "[{\"id\":9,\"libelle\":\"Critère B\"}]"),
                    snap(101L, "[{\"id\":3,\"libelle\":\"Critère A\",\"type\":\"BINAIRE\",\"ponderation\":5}]")));

            List<StationGrilleSnapshotDTO> out = notationService.getGrillesSnapshotByExamen(77L);

            assertThat(out).hasSize(2);
            assertThat(out.get(0).stationId()).isEqualTo(101L);
            assertThat(out.get(0).noteMax()).isEqualTo(20.0);
            assertThat(out.get(0).items().get(0).path("libelle").asText()).isEqualTo("Critère A");
            assertThat(out.get(0).items().get(0).path("ponderation").asInt()).isEqualTo(5);
            assertThat(out.get(1).stationId()).isEqualTo(102L);
            verify(matiereAccessGuard).checkExamenAccess(77L);
        }

        @Test
        @DisplayName("Une ligne au JSON corrompu est SAUTÉE, les autres servies (jamais tout éteindre)")
        void ligneCorrompue_estSautee() {
            when(grilleSnapshotRepository.findByExamenId(77L)).thenReturn(List.of(
                    snap(101L, "{pas du json"),
                    snap(102L, "[]")));

            List<StationGrilleSnapshotDTO> out = notationService.getGrillesSnapshotByExamen(77L);

            assertThat(out).hasSize(1);
            assertThat(out.get(0).stationId()).isEqualTo(102L);
        }

        @Test
        @DisplayName("Examen sans snapshot (avant V19) → liste vide, pas d'erreur")
        void examenSansSnapshot_listeVide() {
            when(grilleSnapshotRepository.findByExamenId(77L)).thenReturn(List.of());

            assertThat(notationService.getGrillesSnapshotByExamen(77L)).isEmpty();
        }
    }
}
