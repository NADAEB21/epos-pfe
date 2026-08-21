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
import tn.epos.scoring_service.entities.Etudiant;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.entities.Lot;
import tn.epos.scoring_service.entities.LotStatus;
import tn.epos.scoring_service.repositories.IEtudiantRepository;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExamenParticipationService - Tests unitaires")
class ExamenParticipationServiceTest {

    @Mock
    private IExamenParticipationRepository repository;

    /** #274 — permissif ici : le perimetre de matiere a ses propres tests. */
    @Mock private MatiereAccessGuard matiereAccessGuard;

    @Mock private IEtudiantRepository etudiantRepository;

    @InjectMocks
    private ExamenParticipationService service;

    private ExamenParticipation participation;
    private Etudiant etudiant;
    private Lot lot;
    private Etudiant etudiantA;
    private Etudiant etudiantB;

    @BeforeEach
    void setUp() {
        // ExamenParticipation : id, examen_id, num_echantillon, note, est_present, etudiant, lot
        etudiant = new Etudiant();
        etudiant.setId(1L);
        etudiant.setNom("Ben Ali");
        etudiant.setPrenom("Mohamed");

        lot = new Lot();
        lot.setId(1L);
        lot.setNumeroLot(1);
        lot.setStatut(LotStatus.EN_ATTENTE);

        participation = new ExamenParticipation();
        participation.setId(1L);
        participation.setExamen_id(10L);
        participation.setNum_echantillon("ECH-001");
        participation.setNote(15.5f);
        participation.setEst_present(true);
        participation.setEtudiant(etudiant);
        participation.setLot(lot);
    }

    @BeforeEach
    void setUpEtudiants() {
        etudiantA = new Etudiant();
        etudiantA.setId(20L);
        etudiantA.setNom("Karoui");
        etudiantA.setPrenom("Sonia");

        etudiantB = new Etudiant();
        etudiantB.setId(21L);
        etudiantB.setNom("Trabelsi");
        etudiantB.setPrenom("Amine");
    }

    @Nested
    @DisplayName("getAll()")
    class GetAll {

        @Test
        @DisplayName("Doit retourner toutes les participations")
        void getAll_devraitRetournerListe() {
            when(repository.findAll()).thenReturn(List.of(participation));

            List<ExamenParticipation> result = service.getAll();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getExamen_id()).isEqualTo(10L);
            assertThat(result.get(0).getNum_echantillon()).isEqualTo("ECH-001");
            verify(repository, times(1)).findAll();
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucune participation")
        void getAll_devraitRetournerListeVide() {
            when(repository.findAll()).thenReturn(List.of());

            List<ExamenParticipation> result = service.getAll();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getById()")
    class GetById {

        @Test
        @DisplayName("Doit retourner la participation si trouvée")
        void getById_devraitRetournerParticipation() {
            when(repository.findById(1L)).thenReturn(Optional.of(participation));

            Optional<ExamenParticipation> result = service.getById(1L);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(1L);
            assertThat(result.get().getEst_present()).isTrue();
        }

        @Test
        @DisplayName("Doit retourner Optional vide si introuvable")
        void getById_devraitRetournerVideSiIntrouvable() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            Optional<ExamenParticipation> result = service.getById(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("save()")
    class Save {

        @Test
        @DisplayName("Doit sauvegarder et retourner la participation")
        void save_devraitSauvegarder() {
            when(repository.save(any(ExamenParticipation.class))).thenReturn(participation);

            ExamenParticipation result = service.save(participation);

            assertThat(result).isNotNull();
            assertThat(result.getNote()).isEqualTo(15.5f);
            assertThat(result.getEtudiant().getNom()).isEqualTo("Ben Ali");
            assertThat(result.getLot().getNumeroLot()).isEqualTo(1);
            verify(repository, times(1)).save(any(ExamenParticipation.class));
        }
    }

    @Nested
    @DisplayName("update() — #274, la garde AVANT la mutation")
    class Update {

        @Test
        @DisplayName("Autorisé : note et présence sont écrites")
        void update_autorise_ecrit() {
            participation.setExamen_id(53L);
            when(repository.findById(1L)).thenReturn(Optional.of(participation));
            when(repository.save(any(ExamenParticipation.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            var result = service.update(1L, 18.0f, true);

            assertThat(result).isPresent();
            assertThat(result.get().getNote()).isEqualTo(18.0f);
            assertThat(result.get().getEst_present()).isTrue();
            verify(matiereAccessGuard).checkExamenAccess(53L);
        }

        /**
         * LE test de non-régression du défaut mesuré en direct le 2026-08-10 : l'appel répondait
         * 403 et la valeur changeait quand même (note 3,25 → 19), parce que le contrôleur avait
         * déjà sali l'entité managée avant que la garde ne s'exécute. Ici on vérifie que l'entité
         * n'est PAS touchée quand le périmètre refuse — donc qu'il n'y a rien à flusher.
         */
        @Test
        @DisplayName("#274 — refusé : l'entité n'est pas modifiée du tout (rien à flusher)")
        void update_refuse_neToucheRienDuTout() {
            participation.setExamen_id(53L);
            participation.setNote(3.25f);
            participation.setEst_present(false);
            when(repository.findById(1L)).thenReturn(Optional.of(participation));
            doThrow(new AccessDeniedException("matière hors périmètre"))
                    .when(matiereAccessGuard).checkExamenAccess(53L);

            assertThatThrownBy(() -> service.update(1L, 19.0f, true))
                    .isInstanceOf(AccessDeniedException.class);

            assertThat(participation.getNote())
                    .as("un refus ne doit rien écrire, pas même en mémoire")
                    .isEqualTo(3.25f);
            assertThat(participation.getEst_present()).isFalse();
            verify(repository, never()).save(any(ExamenParticipation.class));
        }

        @Test
        @DisplayName("Inscription inconnue → vide (le contrôleur en fait un 404)")
        void update_inconnue_vide() {
            when(repository.findById(404L)).thenReturn(Optional.empty());

            assertThat(service.update(404L, 1.0f, true)).isEmpty();

            verifyNoInteractions(matiereAccessGuard);
        }
    }

    @Nested
    @DisplayName("enrolBulk() — #186, inscription groupée")
    class EnrolBulk {
        @Test
        @DisplayName("Vérifie le périmètre de matière AVANT toute écriture")
        void enrolBulk_verifiePerimetreAvantEcriture() {
            doThrow(new AccessDeniedException("matière hors périmètre"))
                    .when(matiereAccessGuard).checkExamenAccess(53L);

            assertThatThrownBy(() -> service.enrolBulk(53L, List.of(20L, 21L)))
                    .isInstanceOf(AccessDeniedException.class);

            verifyNoInteractions(etudiantRepository);
            verify(repository, never()).existsByExamenAndEtudiant(any(), any());
            verify(repository, never()).save(any(ExamenParticipation.class));
        }

        @Test
        @DisplayName("Inscrit les nouveaux étudiants et compte les déjà-inscrits sans erreur")
        void enrolBulk_inscritEtCompteDejaInscrits() {
            when(repository.existsByExamenAndEtudiant(53L, 20L)).thenReturn(false);
            when(repository.existsByExamenAndEtudiant(53L, 21L)).thenReturn(true);
            when(etudiantRepository.findById(20L)).thenReturn(Optional.of(etudiantA));
            when(repository.save(any(ExamenParticipation.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            var result = service.enrolBulk(53L, List.of(20L, 21L));

            assertThat(result.total()).isEqualTo(2);
            assertThat(result.enrolled()).isEqualTo(1);
            assertThat(result.alreadyEnrolled()).isEqualTo(1);
            assertThat(result.errors()).isZero();
            verify(repository, times(1)).save(any(ExamenParticipation.class));
        }

        @Test
        @DisplayName("Un étudiant introuvable devient une ligne ERROR sans interrompre le lot")
        void enrolBulk_etudiantIntrouvable_neBloquePasLeLot() {
            when(repository.existsByExamenAndEtudiant(53L, 20L)).thenReturn(false);
            when(repository.existsByExamenAndEtudiant(53L, 99L)).thenReturn(false);
            when(etudiantRepository.findById(20L)).thenReturn(Optional.of(etudiantA));
            when(etudiantRepository.findById(99L)).thenReturn(Optional.empty());
            when(repository.save(any(ExamenParticipation.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            var result = service.enrolBulk(53L, List.of(20L, 99L));

            assertThat(result.enrolled()).isEqualTo(1);
            assertThat(result.errors()).isEqualTo(1);
            assertThat(result.lignes())
                    .anySatisfy(l -> assertThat(l.statut()).isEqualTo("ERROR"));
        }

        @Test
        @DisplayName("Dédoublonne les ids en double dans la sélection")
        void enrolBulk_dedoublonneLesIds() {
            when(repository.existsByExamenAndEtudiant(53L, 20L)).thenReturn(false);
            when(etudiantRepository.findById(20L)).thenReturn(Optional.of(etudiantA));
            when(repository.save(any(ExamenParticipation.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            var result = service.enrolBulk(53L, List.of(20L, 20L, 20L));

            assertThat(result.total()).isEqualTo(1);
            verify(repository, times(1)).save(any(ExamenParticipation.class));
        }

        @Test
        @DisplayName("Liste null → résultat vide, pas de NPE")
        void enrolBulk_listeNulle_neLevePas() {
            var result = service.enrolBulk(53L, null);

            assertThat(result.total()).isZero();
            verifyNoInteractions(etudiantRepository);
        }
    }

    @Nested
    @DisplayName("delete()")
    class Delete {

        /**
         * #274 — on charge l'inscription avant de la supprimer : {@code deleteById} ne dit pas
         * de quel examen elle relevait, donc ne permet aucun contrôle de périmètre.
         */
        @Test
        @DisplayName("#274 — charge l'inscription, vérifie le périmètre, PUIS supprime")
        void delete_devraitVerifierLePerimetrePuisSupprimer() {
            ExamenParticipation p = new ExamenParticipation();
            p.setId(1L);
            p.setExamen_id(42L);
            when(repository.findById(1L)).thenReturn(Optional.of(p));

            service.delete(1L);

            verify(matiereAccessGuard).checkExamenAccess(42L);
            verify(repository, times(1)).delete(p);
        }

        @Test
        @DisplayName("Une inscription inconnue ne supprime rien et ne lève pas")
        void delete_inconnue_neSupprimeRien() {
            when(repository.findById(404L)).thenReturn(Optional.empty());

            service.delete(404L);

            verify(repository, never()).delete(any(ExamenParticipation.class));
        }
    }
}
