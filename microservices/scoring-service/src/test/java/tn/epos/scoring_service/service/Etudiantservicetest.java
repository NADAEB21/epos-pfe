package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.scoring_service.dto.ImportEtudiantRequest;
import tn.epos.scoring_service.dto.ImportResult;
import tn.epos.scoring_service.entities.Etudiant;
import tn.epos.scoring_service.entities.ExamenParticipation;
import tn.epos.scoring_service.repositories.IEtudiantRepository;
import tn.epos.scoring_service.repositories.IExamenParticipationRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EtudiantService - Tests unitaires")
class EtudiantServiceTest {

    @Mock
    private IEtudiantRepository etudiantRepository;

    @Mock
    private IExamenParticipationRepository participationRepository;

    @InjectMocks
    private EtudiantService etudiantService;

    private Etudiant etudiant;

    @BeforeEach
    void setUp() {
        // Etudiant : id, numero_inscription, nom, prenom, participations
        etudiant = new Etudiant();
        etudiant.setId(1L);
        etudiant.setNumero_inscription("2024-001");
        etudiant.setNom("Ben Ali");
        etudiant.setPrenom("Mohamed");
    }

    @Nested
    @DisplayName("getAllEtudiants()")
    class GetAllEtudiants {

        @Test
        @DisplayName("Doit retourner la liste de tous les étudiants")
        void getAllEtudiants_devraitRetournerListe() {
            when(etudiantRepository.findAll()).thenReturn(List.of(etudiant));

            List<Etudiant> result = etudiantService.getAllEtudiants();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNom()).isEqualTo("Ben Ali");
            assertThat(result.get(0).getNumero_inscription()).isEqualTo("2024-001");
            verify(etudiantRepository, times(1)).findAll();
        }

        @Test
        @DisplayName("Doit retourner une liste vide si aucun étudiant")
        void getAllEtudiants_devraitRetournerListeVide() {
            when(etudiantRepository.findAll()).thenReturn(List.of());

            List<Etudiant> result = etudiantService.getAllEtudiants();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getEtudiantById()")
    class GetEtudiantById {

        @Test
        @DisplayName("Doit retourner l'étudiant si trouvé")
        void getEtudiantById_devraitRetournerEtudiant() {
            when(etudiantRepository.findById(1L)).thenReturn(Optional.of(etudiant));

            Optional<Etudiant> result = etudiantService.getEtudiantById(1L);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(1L);
            assertThat(result.get().getPrenom()).isEqualTo("Mohamed");
        }

        @Test
        @DisplayName("Doit retourner Optional vide si introuvable")
        void getEtudiantById_devraitRetournerVideSiIntrouvable() {
            when(etudiantRepository.findById(99L)).thenReturn(Optional.empty());

            Optional<Etudiant> result = etudiantService.getEtudiantById(99L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("saveEtudiant()")
    class SaveEtudiant {

        @Test
        @DisplayName("Doit sauvegarder et retourner l'étudiant")
        void saveEtudiant_devraitSauvegarder() {
            when(etudiantRepository.save(any(Etudiant.class))).thenReturn(etudiant);

            Etudiant result = etudiantService.saveEtudiant(etudiant);

            assertThat(result).isNotNull();
            assertThat(result.getNom()).isEqualTo("Ben Ali");
            assertThat(result.getPrenom()).isEqualTo("Mohamed");
            verify(etudiantRepository, times(1)).save(any(Etudiant.class));
        }
    }

    @Nested
    @DisplayName("deleteEtudiant()")
    class DeleteEtudiant {

        @Test
        @DisplayName("Doit appeler deleteById avec le bon ID")
        void deleteEtudiant_devraitAppelerDeleteById() {
            doNothing().when(etudiantRepository).deleteById(1L);

            etudiantService.deleteEtudiant(1L);

            verify(etudiantRepository, times(1)).deleteById(1L);
        }
    }

    @Nested
    @DisplayName("importStudents() - import en masse (gap #11)")
    class ImportStudents {

        private static final Long EXAM = 10L;

        @Test
        @DisplayName("Nouvel étudiant : crée le répertoire ET inscrit -> CREATED")
        void importStudents_nouveau_devraitCreerEtInscrire() {
            when(etudiantRepository.findByNumeroInscription("N-1")).thenReturn(List.of());
            when(etudiantRepository.save(any(Etudiant.class))).thenAnswer(inv -> {
                Etudiant e = inv.getArgument(0);
                e.setId(42L);
                return e;
            });
            when(participationRepository.existsByExamenAndEtudiant(EXAM, 42L)).thenReturn(false);

            ImportResult result = etudiantService.importStudents(EXAM,
                    List.of(new ImportEtudiantRequest("Nom", "Prenom", "N-1")));

            assertThat(result.total()).isEqualTo(1);
            assertThat(result.created()).isEqualTo(1);
            assertThat(result.enrolled()).isZero();
            assertThat(result.alreadyEnrolled()).isZero();
            assertThat(result.errors()).isZero();
            assertThat(result.rows().get(0).statut()).isEqualTo("CREATED");
            verify(participationRepository, times(1)).save(any(ExamenParticipation.class));
        }

        @Test
        @DisplayName("Étudiant existant non inscrit : réutilise + inscrit -> ENROLLED")
        void importStudents_existantNonInscrit_devraitInscrire() {
            when(etudiantRepository.findByNumeroInscription("2024-001")).thenReturn(List.of(etudiant));
            when(participationRepository.existsByExamenAndEtudiant(EXAM, 1L)).thenReturn(false);

            ImportResult result = etudiantService.importStudents(EXAM,
                    List.of(new ImportEtudiantRequest("Ben Ali", "Mohamed", "2024-001")));

            assertThat(result.created()).isZero();
            assertThat(result.enrolled()).isEqualTo(1);
            assertThat(result.rows().get(0).statut()).isEqualTo("ENROLLED");
            // existing student reused, NOT re-created
            verify(etudiantRepository, never()).save(any(Etudiant.class));
            verify(participationRepository, times(1)).save(any(ExamenParticipation.class));
        }

        @Test
        @DisplayName("Étudiant déjà inscrit : ignore -> ALREADY_ENROLLED")
        void importStudents_dejaInscrit_devraitIgnorer() {
            when(etudiantRepository.findByNumeroInscription("2024-001")).thenReturn(List.of(etudiant));
            when(participationRepository.existsByExamenAndEtudiant(EXAM, 1L)).thenReturn(true);

            ImportResult result = etudiantService.importStudents(EXAM,
                    List.of(new ImportEtudiantRequest("Ben Ali", "Mohamed", "2024-001")));

            assertThat(result.alreadyEnrolled()).isEqualTo(1);
            assertThat(result.rows().get(0).statut()).isEqualTo("ALREADY_ENROLLED");
            verify(participationRepository, never()).save(any(ExamenParticipation.class));
        }

        @Test
        @DisplayName("Ligne sans numéro ou sans nom -> ERROR, sans toucher la base")
        void importStudents_champsManquants_devraitEchouer() {
            ImportResult result = etudiantService.importStudents(EXAM, List.of(
                    new ImportEtudiantRequest("", "Prenom", "N-2"),   // nom manquant
                    new ImportEtudiantRequest("Nom", "Prenom", "  ")   // numéro manquant
            ));

            assertThat(result.errors()).isEqualTo(2);
            assertThat(result.created()).isZero();
            assertThat(result.enrolled()).isZero();
            assertThat(result.rows()).allMatch(r -> "ERROR".equals(r.statut()));
            verify(etudiantRepository, never()).save(any(Etudiant.class));
            verify(participationRepository, never()).save(any(ExamenParticipation.class));
        }

        @Test
        @DisplayName("Lot mixte : compteurs cohérents (somme == total)")
        void importStudents_mixte_compteursCoherents() {
            // row1 new+enrol, row2 existing+enrol, row3 already, row4 error
            when(etudiantRepository.findByNumeroInscription("NEW")).thenReturn(List.of());
            when(etudiantRepository.save(any(Etudiant.class))).thenAnswer(inv -> {
                Etudiant e = inv.getArgument(0);
                e.setId(99L);
                return e;
            });
            when(etudiantRepository.findByNumeroInscription("2024-001")).thenReturn(List.of(etudiant));
            when(participationRepository.existsByExamenAndEtudiant(eq(EXAM), anyLong()))
                    .thenReturn(false)   // row1 (id 99)
                    .thenReturn(false)   // row2 (id 1)
                    .thenReturn(true);   // row3 (id 1) already enrolled

            ImportResult result = etudiantService.importStudents(EXAM, List.of(
                    new ImportEtudiantRequest("New", "Student", "NEW"),
                    new ImportEtudiantRequest("Ben Ali", "Mohamed", "2024-001"),
                    new ImportEtudiantRequest("Ben Ali", "Mohamed", "2024-001"),
                    new ImportEtudiantRequest("", "", "")
            ));

            assertThat(result.total()).isEqualTo(4);
            assertThat(result.created()).isEqualTo(1);
            assertThat(result.enrolled()).isEqualTo(1);
            assertThat(result.alreadyEnrolled()).isEqualTo(1);
            assertThat(result.errors()).isEqualTo(1);
            assertThat(result.created() + result.enrolled()
                    + result.alreadyEnrolled() + result.errors()).isEqualTo(result.total());
        }
    }
}
