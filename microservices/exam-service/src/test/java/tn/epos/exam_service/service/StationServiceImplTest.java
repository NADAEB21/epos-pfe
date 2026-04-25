package tn.epos.exam_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;
import tn.epos.exam_service.dto.request.StationRequest;
import tn.epos.exam_service.dto.response.StationResponse;
import tn.epos.exam_service.entities.Examen;
import tn.epos.exam_service.entities.Station;
import tn.epos.exam_service.enums.StatutExamen;
import tn.epos.exam_service.enums.TypeStation;
import tn.epos.exam_service.exception.BusinessException;
import tn.epos.exam_service.exception.ResourceNotFoundException;
import tn.epos.exam_service.repositories.ExamenRepository;
import tn.epos.exam_service.repositories.StationRepository;
import tn.epos.exam_service.services.impl.StationServiceImpl;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
@DisplayName("StationService - Tests unitaires")
class StationServiceImplTest {

    @Mock
    private StationRepository stationRepository;

    @Mock
    private ExamenRepository examenRepository;

    @InjectMocks
    private StationServiceImpl stationService;

    private Examen examenBrouillon;
    private Station station;
    private StationRequest stationRequest;

    @BeforeEach
    void setUp() {
        examenBrouillon = Examen.builder()
                .id(1L)
                .nom("Examen Test")
                .matiere("Chimie")
                .dateExamen(LocalDate.now())
                .statut(StatutExamen.BROUILLON)
                .build();

        station = Station.builder()
                .id(1L)
                .nom("Station 3")
                .type(TypeStation.PRATIQUE)
                .ordre(1)
                .examen(examenBrouillon)
                .build();

        stationRequest = new StationRequest();
        stationRequest.setNom("Station 3");
        stationRequest.setType(TypeStation.PRATIQUE);
        stationRequest.setDescription("Description");
    }

    // ================================================================
    // AJOUTER
    // ================================================================

    @Nested
    @DisplayName("ajouter()")
    class Ajouter {

        @Test
        @DisplayName("Doit ajouter une station si examen BROUILLON")
        void ajouter_devraitAjouterStation() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));
            when(stationRepository.existsByNomAndExamenId(any(), anyLong())).thenReturn(false);
            when(stationRepository.countByExamenId(1L)).thenReturn(0L);
            when(stationRepository.save(any(Station.class))).thenReturn(station);

            StationResponse result = stationService.ajouter(1L, stationRequest);

            assertThat(result).isNotNull();
            assertThat(result.getNom()).isEqualTo("Station 3");
            verify(stationRepository).save(any(Station.class));
        }

        @Test
        @DisplayName("Doit lever BusinessException si doublon de nom")
        void ajouter_devraitLeverExceptionSiDoublon() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));
            when(stationRepository.existsByNomAndExamenId("Station 3", 1L)).thenReturn(true);

            assertThatThrownBy(() -> stationService.ajouter(1L, stationRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Station 3");
        }

        @Test
        @DisplayName("Doit lever BusinessException si examen EN_COURS")
        void ajouter_devraitLeverExceptionSiExamenEnCours() {
            examenBrouillon.setStatut(StatutExamen.EN_COURS);
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));

            assertThatThrownBy(() -> stationService.ajouter(1L, stationRequest))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si examen introuvable")
        void ajouter_devraitLeverExceptionSiExamenIntrouvable() {
            when(examenRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> stationService.ajouter(99L, stationRequest))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("L'ordre doit être calculé automatiquement")
        void ajouter_devraitCalculerOrdreAutomatiquement() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));
            when(stationRepository.existsByNomAndExamenId(any(), anyLong())).thenReturn(false);
            when(stationRepository.countByExamenId(1L)).thenReturn(2L); // 2 stations déjà
            when(stationRepository.save(any(Station.class))).thenAnswer(invocation -> {
                Station s = invocation.getArgument(0);
                assertThat(s.getOrdre()).isEqualTo(3); // ordre = 2 + 1
                s.setId(3L);
                return s;
            });

            stationService.ajouter(1L, stationRequest);
        }
    }

    // ================================================================
    // LISTER
    // ================================================================

    @Nested
    @DisplayName("listerParExamen()")
    class ListerParExamen {

        @Test
        @DisplayName("Doit retourner les stations triées par ordre")
        void listerParExamen_devraitRetournerStations() {
            when(examenRepository.existsById(1L)).thenReturn(true);
            when(stationRepository.findByExamenIdOrderByOrdreAsc(1L))
                    .thenReturn(List.of(station));

            List<StationResponse> result = stationService.listerParExamen(1L);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si examen introuvable")
        void listerParExamen_devraitLeverExceptionSiExamenIntrouvable() {
            when(examenRepository.existsById(99L)).thenReturn(false);

            assertThatThrownBy(() -> stationService.listerParExamen(99L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ================================================================
    // TROUVER PAR ID
    // ================================================================

    @Nested
    @DisplayName("trouverParId()")
    class TrouverParId {

        @Test
        @DisplayName("Doit retourner la station si trouvée")
        void trouverParId_devraitRetournerStation() {
            when(stationRepository.findByIdWithGrille(1L)).thenReturn(Optional.of(station));

            StationResponse result = stationService.trouverParId(1L);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si introuvable")
        void trouverParId_devraitLeverExceptionSiIntrouvable() {
            when(stationRepository.findByIdWithGrille(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> stationService.trouverParId(99L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ================================================================
    // MODIFIER
    // ================================================================

    @Nested
    @DisplayName("modifier()")
    class Modifier {

        @Test
        @DisplayName("Doit modifier la station si examen modifiable")
        void modifier_devraitModifierStation() {
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(stationRepository.existsByNomAndExamenId(any(), anyLong())).thenReturn(false);
            when(stationRepository.save(any())).thenReturn(station);

            StationResponse result = stationService.modifier(1L, stationRequest);

            assertThat(result).isNotNull();
            verify(stationRepository).save(any());
        }

        @Test
        @DisplayName("Doit lever BusinessException si examen non modifiable")
        void modifier_devraitLeverExceptionSiExamenNonModifiable() {
            examenBrouillon.setStatut(StatutExamen.EN_COURS);
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));

            assertThatThrownBy(() -> stationService.modifier(1L, stationRequest))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ================================================================
    // SUPPRIMER
    // ================================================================

    @Nested
    @DisplayName("supprimer()")
    class Supprimer {

        @Test
        @DisplayName("Doit supprimer et réordonner les stations restantes")
        void supprimer_devraitSupprimerEtReordonner() {
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(stationRepository.findByExamenIdOrderByOrdreAsc(1L)).thenReturn(List.of());

            stationService.supprimer(1L);

            verify(stationRepository).delete(station);
        }

        @Test
        @DisplayName("Doit lever BusinessException si examen EN_COURS")
        void supprimer_devraitLeverExceptionSiEnCours() {
            examenBrouillon.setStatut(StatutExamen.EN_COURS);
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));

            assertThatThrownBy(() -> stationService.supprimer(1L))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si station introuvable")
        void supprimer_devraitLeverExceptionSiIntrouvable() {
            when(stationRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> stationService.supprimer(99L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
