package tn.epos.exam_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import tn.epos.exam_service.config.MatiereAccessChecker;
import tn.epos.exam_service.dto.request.StationRequest;
import tn.epos.exam_service.dto.response.StationResponse;
import tn.epos.exam_service.entities.Examen;
import tn.epos.exam_service.entities.Station;
import tn.epos.exam_service.enums.StatutExamen;
import tn.epos.exam_service.enums.TypeStation;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.exam_service.exception.ConflictException;
import tn.epos.exam_service.repositories.ExamenRepository;
import tn.epos.exam_service.repositories.StationRepository;
import tn.epos.exam_service.services.impl.StationServiceImpl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import static org.mockito.ArgumentMatchers.eq;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StationService - Tests unitaires")
class StationServiceImplTest {

    @Mock
    private StationRepository stationRepository;

    @Mock
    private ExamenRepository examenRepository;

    @Mock
    private MatiereAccessChecker matiereAccessChecker;

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
                .matiereId(1L)
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
            Page<Station> page = new PageImpl<>(List.of(station));
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));
            when(stationRepository.findByExamenIdOrderByOrdreAsc(eq(1L), any(Pageable.class)))
                    .thenReturn(page);

            Page<StationResponse> result = stationService.listerParExamen(1L, Pageable.unpaged());

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si examen introuvable")
        void listerParExamen_devraitLeverExceptionSiExamenIntrouvable() {
            when(examenRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> stationService.listerParExamen(99L, Pageable.unpaged()))
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

    @Nested
    @DisplayName("affecterEvaluateurs()")
    class AffecterEvaluateurs {

        @Test
        @DisplayName("Doit affecter la liste des évaluateurs")
        void affecterEvaluateurs_devraitMettreAJourLaListe() {
            station.setEvaluateurIds(new ArrayList<>());
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(stationRepository.save(any(Station.class))).thenReturn(station);

            StationResponse result = stationService.affecterEvaluateurs(1L, List.of(10L, 20L));

            assertThat(result).isNotNull();
            verify(stationRepository).save(argThat(s ->
                    s.getEvaluateurIds().contains(10L) &&
                            s.getEvaluateurIds().contains(20L)));
        }

        @Test
        @DisplayName("Doit vider la liste si on passe une liste vide")
        void affecterEvaluateurs_listeVide_doitVider() {
            station.setEvaluateurIds(new ArrayList<>(List.of(10L, 20L)));
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(stationRepository.save(any())).thenReturn(station);

            stationService.affecterEvaluateurs(1L, List.of());

            verify(stationRepository).save(argThat(s ->
                    s.getEvaluateurIds().isEmpty()));
        }

        @Test
        @DisplayName("Doit traiter null comme liste vide")
        void affecterEvaluateurs_null_doitVider() {
            station.setEvaluateurIds(new ArrayList<>(List.of(10L)));
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(stationRepository.save(any())).thenReturn(station);

            stationService.affecterEvaluateurs(1L, null);

            verify(stationRepository).save(argThat(s ->
                    s.getEvaluateurIds().isEmpty()));
        }

        @Test
        @DisplayName("Doit lever BusinessException si examen non modifiable")
        void affecterEvaluateurs_examenVerrouille_devraitEchouer() {
            examenBrouillon.setStatut(StatutExamen.EN_COURS);
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));

            assertThatThrownBy(() ->
                    stationService.affecterEvaluateurs(1L, List.of(10L)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("EN_COURS");
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si station introuvable")
        void affecterEvaluateurs_stationIntrouvable_devraitEchouer() {
            when(stationRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    stationService.affecterEvaluateurs(99L, List.of(10L)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Doit permettre plusieurs évaluateurs sur une même station")
        void affecterEvaluateurs_plusieursEvaluateurs_doitTousLesAffecter() {
            station.setEvaluateurIds(new ArrayList<>());
            List<Long> evaluateurs = List.of(1L, 2L, 3L, 4L);

            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(stationRepository.save(any())).thenReturn(station);

            stationService.affecterEvaluateurs(1L, evaluateurs);

            verify(stationRepository).save(argThat(s ->
                    s.getEvaluateurIds().size() == 4));
        }
    }

    // ================================================================
    // #163 — un évaluateur ne peut tenir qu'UNE station par examen.
    // Conflit avec une AUTRE station du même examen → 409 ConflictException.
    // ================================================================

    @Nested
    @DisplayName("Unicité évaluateur par examen (#163)")
    class EvaluateurUniciteParExamen {

        private Station autreStation(Long id, String nom, Long... evaluateurs) {
            return Station.builder()
                    .id(id)
                    .nom(nom)
                    .type(TypeStation.PRATIQUE)
                    .ordre(2)
                    .examen(examenBrouillon)
                    .evaluateurIds(new ArrayList<>(List.of(evaluateurs)))
                    .build();
        }

        @Test
        @DisplayName("affecterEvaluateurs() → 409 si l'évaluateur est déjà sur une autre station")
        void affecter_conflitAutreStation_devraitLever409() {
            station.setEvaluateurIds(new ArrayList<>());
            Station autre = autreStation(2L, "Station 2", 10L);
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(stationRepository.findByExamenIdOrderByOrdreAsc(1L))
                    .thenReturn(List.of(station, autre));

            assertThatThrownBy(() -> stationService.affecterEvaluateurs(1L, List.of(10L)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Station 2")
                    .hasMessageContaining("une seule station");
            verify(stationRepository, never()).save(any());
        }

        @Test
        @DisplayName("affecterEvaluateurs() → OK si l'évaluateur n'est nulle part ailleurs")
        void affecter_pasDeConflit_devraitReussir() {
            station.setEvaluateurIds(new ArrayList<>());
            Station autre = autreStation(2L, "Station 2", 99L);
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(stationRepository.findByExamenIdOrderByOrdreAsc(1L))
                    .thenReturn(List.of(station, autre));
            when(stationRepository.save(any())).thenReturn(station);

            StationResponse r = stationService.affecterEvaluateurs(1L, List.of(10L));

            assertThat(r).isNotNull();
            verify(stationRepository).save(argThat(s -> s.getEvaluateurIds().contains(10L)));
        }

        @Test
        @DisplayName("affecterEvaluateurs() → OK en réaffectant le même évaluateur à SA station (exclusion de soi)")
        void affecter_memeStation_pasDeFauxConflit() {
            station.setEvaluateurIds(new ArrayList<>(List.of(10L)));
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            // La seule station portant le 10 est la station courante elle-même.
            when(stationRepository.findByExamenIdOrderByOrdreAsc(1L))
                    .thenReturn(List.of(station));
            when(stationRepository.save(any())).thenReturn(station);

            StationResponse r = stationService.affecterEvaluateurs(1L, List.of(10L));

            assertThat(r).isNotNull();
            verify(stationRepository).save(any());
        }

        @Test
        @DisplayName("ajouter() → 409 si la nouvelle station réclame un évaluateur déjà pris")
        void ajouter_conflit_devraitLever409() {
            stationRequest.setEvaluateurIds(List.of(10L));
            Station autre = autreStation(2L, "Station 2", 10L);
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));
            when(stationRepository.existsByNomAndExamenId(any(), anyLong())).thenReturn(false);
            when(stationRepository.findByExamenIdOrderByOrdreAsc(1L))
                    .thenReturn(List.of(autre));

            assertThatThrownBy(() -> stationService.ajouter(1L, stationRequest))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Station 2");
            verify(stationRepository, never()).save(any());
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

    // ================================================================
    // #96 — per-matiere authz: MatiereAccessChecker is consulted after
    // every entity load and an out-of-scope caller is rejected with 403.
    // ================================================================

    @Nested
    @DisplayName("MatiereAccessChecker enforcement (#96)")
    class MatiereScopeEnforcement {

        @Test
        @DisplayName("ajouter() rejects when caller is out of scope")
        void ajouter_outOfScope_devraitRefuser() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            assertThatThrownBy(() -> stationService.ajouter(1L, stationRequest))
                    .isInstanceOf(AccessDeniedException.class);
            verify(stationRepository, never()).save(any());
        }

        @Test
        @DisplayName("listerParExamen() rejects when caller is out of scope")
        void listerParExamen_outOfScope_devraitRefuser() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            assertThatThrownBy(() -> stationService.listerParExamen(1L, Pageable.unpaged()))
                    .isInstanceOf(AccessDeniedException.class);
            verify(stationRepository, never()).findByExamenIdOrderByOrdreAsc(anyLong(), any(Pageable.class));
        }

        /**
         * Lecture : la portée par matière reste appliquée — mais via {@code checkReadAccess},
         * qui autorise en plus l'ÉVALUATEUR (#190 : il doit lire le nom de la station qu'il
         * occupe, comme il lit déjà sa grille). Un RESPONSABLE hors périmètre est toujours
         * refusé : c'est ce que ce test verrouille.
         */
        @Test
        @DisplayName("trouverParId() rejects when caller is out of scope")
        void trouverParId_outOfScope_devraitRefuser() {
            when(stationRepository.findByIdWithGrille(1L)).thenReturn(Optional.of(station));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkReadAccess(1L);

            assertThatThrownBy(() -> stationService.trouverParId(1L))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("modifier() rejects when caller is out of scope")
        void modifier_outOfScope_devraitRefuser() {
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            assertThatThrownBy(() -> stationService.modifier(1L, stationRequest))
                    .isInstanceOf(AccessDeniedException.class);
            verify(stationRepository, never()).save(any());
        }

        @Test
        @DisplayName("affecterEvaluateurs() rejects when caller is out of scope")
        void affecterEvaluateurs_outOfScope_devraitRefuser() {
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            assertThatThrownBy(() -> stationService.affecterEvaluateurs(1L, List.of(7L)))
                    .isInstanceOf(AccessDeniedException.class);
            verify(stationRepository, never()).save(any());
        }

        @Test
        @DisplayName("supprimer() rejects when caller is out of scope")
        void supprimer_outOfScope_devraitRefuser() {
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            assertThatThrownBy(() -> stationService.supprimer(1L))
                    .isInstanceOf(AccessDeniedException.class);
            verify(stationRepository, never()).delete(any());
        }
    }
}
