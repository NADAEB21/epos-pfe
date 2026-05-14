package tn.epos.scoring_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tn.epos.scoring_service.entities.Etudiant;
import tn.epos.scoring_service.repositories.IEtudiantRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EtudiantService - Tests unitaires")
class EtudiantServiceTest {

    @Mock
    private IEtudiantRepository etudiantRepository;

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
}
