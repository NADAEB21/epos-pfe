package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import tn.epos.scoring_service.config.EvaluateurScopeChecker;
import tn.epos.scoring_service.dto.ExamenResultDTO;
import tn.epos.scoring_service.entities.Etudiant;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.entities.Rotation;
import tn.epos.scoring_service.entities.RotationAssignment;
import tn.epos.scoring_service.repositories.INotationRepository;
import tn.epos.scoring_service.repositories.IRotationAssignmentRepository;

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

        @Test
        @DisplayName("Doit appeler deleteById avec le bon ID")
        void delete_devraitAppelerDeleteById() {
            doNothing().when(repository).deleteById(1L);

            notationService.delete(1L);

            verify(repository, times(1)).deleteById(1L);
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
    }

    @Nested
    @DisplayName("verrouiller()")
    class Verrouiller {

        @Test
        @DisplayName("Doit passer verouillee à true et sauvegarder")
        void verrouiller_devraitVerrouillerEtSauvegarder() {
            when(repository.findById(1L)).thenReturn(Optional.of(notation));
            when(repository.save(any(Notation.class))).thenAnswer(inv -> inv.getArgument(0));

            Notation result = notationService.verrouiller(1L);

            assertThat(result.getVerouillee()).isTrue();
            verify(repository).save(any(Notation.class));
        }

        @Test
        @DisplayName("Une notation déjà verrouillée reste verrouillée après un second appel")
        void verrouiller_devraitResterVerrouilleeSiDejaVerrouillee() {
            notation.setVerouillee(true);
            when(repository.findById(1L)).thenReturn(Optional.of(notation));
            when(repository.save(any(Notation.class))).thenAnswer(inv -> inv.getArgument(0));

            Notation result = notationService.verrouiller(1L);

            assertThat(result.getVerouillee()).isTrue();
        }

        @Test
        @DisplayName("Doit lever RuntimeException si notation introuvable lors du verrouillage")
        void verrouiller_devraitLeverExceptionSiIntrouvable() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> notationService.verrouiller(99L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("99");
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
}
