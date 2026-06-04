package tn.epos.exam_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import tn.epos.exam_service.dto.request.ExamenRequest;
import tn.epos.exam_service.dto.response.ExamenResponse;
import tn.epos.exam_service.entities.Examen;
import tn.epos.exam_service.entities.Station;
import tn.epos.exam_service.enums.StatutExamen;
import tn.epos.common.exception.BusinessException;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.exam_service.config.MatiereAccessChecker;
import tn.epos.exam_service.repositories.ExamenRepository;
import tn.epos.exam_service.services.impl.ExamenServiceImpl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import static org.mockito.ArgumentMatchers.eq;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExamenService - Tests unitaires")
class ExamenServiceImplTest {

    @Mock
    private ExamenRepository examenRepository;

    @Mock
    private MatiereAccessChecker matiereAccessChecker;

    @InjectMocks
    private ExamenServiceImpl examenService;

    private Examen examenBrouillon;
    private ExamenRequest examenRequest;

    @BeforeEach
    void setUp() {
        // Injection du uploadDir via réflexion (champ @Value)
        try {
            var field = ExamenServiceImpl.class.getDeclaredField("uploadDir");
            field.setAccessible(true);
            field.set(examenService, "uploads/");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        examenRequest = new ExamenRequest();
        examenRequest.setNom("Examen Test");
        examenRequest.setMatiereId(1L);
        examenRequest.setDateExamen(LocalDate.of(2024, 6, 15));
        examenRequest.setDureeStationMin(15);
        examenRequest.setNbEtudiantsParStation(4);

        examenBrouillon = Examen.builder()
                .id(1L)
                .nom("Examen Test")
                .matiereId(1L)
                .dateExamen(LocalDate.of(2024, 6, 15))
                .dureeStationMin(15)
                .nbEtudiantsParStation(4)
                .statut(StatutExamen.BROUILLON)
                .build();
    }

    // CREER

    @Nested
    @DisplayName("creer()")
    class Creer {

        @Test
        @DisplayName("Doit créer un examen avec statut BROUILLON")
        void creer_devraitCreerExamen() {
            when(examenRepository.save(any(Examen.class))).thenReturn(examenBrouillon);

            ExamenResponse result = examenService.creer(examenRequest);

            assertThat(result).isNotNull();
            assertThat(result.getNom()).isEqualTo("Examen Test");
            assertThat(result.getStatut()).isEqualTo(StatutExamen.BROUILLON);
            verify(examenRepository, times(1)).save(any(Examen.class));
        }

        @Test
        @DisplayName("Doit appeler save() exactement une fois")
        void creer_devraitAppelerSaveUneFois() {
            when(examenRepository.save(any(Examen.class))).thenReturn(examenBrouillon);

            examenService.creer(examenRequest);

            verify(examenRepository, times(1)).save(any(Examen.class));
        }
    }

    // LISTER

    @Nested
    @DisplayName("listerTous()")
    class ListerTous {

        @Test
        @DisplayName("SUPER_ADMIN: doit retourner la liste de tous les examens")
        void listerTous_superAdmin_devraitRetournerListe() {
            Page<Examen> pageEntite = new PageImpl<>(List.of(examenBrouillon));
            when(matiereAccessChecker.isUnrestricted()).thenReturn(true);
            when(examenRepository.findAll(any(Pageable.class))).thenReturn(pageEntite);

            Page<ExamenResponse> result = examenService.listerTous(Pageable.unpaged());

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getNom()).isEqualTo("Examen Test");
        }

        @Test
        @DisplayName("SUPER_ADMIN: doit retourner liste vide si aucun examen")
        void listerTous_superAdmin_devraitRetournerListeVide() {
            Page<Examen> pageVide = new PageImpl<>(List.of());
            when(matiereAccessChecker.isUnrestricted()).thenReturn(true);
            when(examenRepository.findAll(any(Pageable.class))).thenReturn(pageVide);

            Page<ExamenResponse> result = examenService.listerTous(Pageable.unpaged());

            assertThat(result.getContent()).isEmpty();
        }

        @Test
        @DisplayName("SUPER_ADMIN: doit filtrer par statut sans filtre matière")
        void listerParStatut_superAdmin_devraitFiltrerParStatut() {
            Page<Examen> page = new PageImpl<>(List.of(examenBrouillon));
            when(matiereAccessChecker.isUnrestricted()).thenReturn(true);
            when(examenRepository.findByStatut(eq(StatutExamen.BROUILLON), any(Pageable.class)))
                    .thenReturn(page);

            Page<ExamenResponse> result = examenService.listerParStatut(
                    StatutExamen.BROUILLON, Pageable.unpaged());

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getStatut()).isEqualTo(StatutExamen.BROUILLON);
        }

        // ── #95 — RESPONSABLE_MATIERE scope filter ──────────────────────────

        @Test
        @DisplayName("#95 — RESPONSABLE: doit appeler findByMatiereIdIn avec son périmètre")
        void listerTous_responsable_devraitFiltrerSurSonPerimetre() {
            Page<Examen> page = new PageImpl<>(List.of(examenBrouillon));
            when(matiereAccessChecker.isUnrestricted()).thenReturn(false);
            when(matiereAccessChecker.getAccessibleMatiereIds()).thenReturn(Set.of(1L, 7L));
            when(examenRepository.findByMatiereIdIn(eq(Set.of(1L, 7L)), any(Pageable.class)))
                    .thenReturn(page);

            Page<ExamenResponse> result = examenService.listerTous(Pageable.unpaged());

            assertThat(result.getContent()).hasSize(1);
            verify(examenRepository, never()).findAll(any(Pageable.class));
        }

        @Test
        @DisplayName("#95 — RESPONSABLE sans périmètre: doit retourner page vide sans hit DB")
        void listerTous_responsableSansPerimetre_devraitRetournerVide() {
            when(matiereAccessChecker.isUnrestricted()).thenReturn(false);
            when(matiereAccessChecker.getAccessibleMatiereIds()).thenReturn(Set.of());

            Page<ExamenResponse> result = examenService.listerTous(Pageable.unpaged());

            assertThat(result.getContent()).isEmpty();
            verify(examenRepository, never()).findAll(any(Pageable.class));
            verify(examenRepository, never()).findByMatiereIdIn(any(), any(Pageable.class));
        }

        @Test
        @DisplayName("#95 — RESPONSABLE: listerParStatut doit appeler findByMatiereIdInAndStatut")
        void listerParStatut_responsable_devraitFiltrerScopePlusStatut() {
            Page<Examen> page = new PageImpl<>(List.of(examenBrouillon));
            when(matiereAccessChecker.isUnrestricted()).thenReturn(false);
            when(matiereAccessChecker.getAccessibleMatiereIds()).thenReturn(Set.of(1L));
            when(examenRepository.findByMatiereIdInAndStatut(
                    eq(Set.of(1L)), eq(StatutExamen.BROUILLON), any(Pageable.class)))
                    .thenReturn(page);

            Page<ExamenResponse> result = examenService.listerParStatut(
                    StatutExamen.BROUILLON, Pageable.unpaged());

            assertThat(result.getContent()).hasSize(1);
            verify(examenRepository, never()).findByStatut(any(), any(Pageable.class));
        }

        @Test
        @DisplayName("#95 — RESPONSABLE sans périmètre: listerParStatut doit retourner vide sans hit DB")
        void listerParStatut_responsableSansPerimetre_devraitRetournerVide() {
            when(matiereAccessChecker.isUnrestricted()).thenReturn(false);
            when(matiereAccessChecker.getAccessibleMatiereIds()).thenReturn(Set.of());

            Page<ExamenResponse> result = examenService.listerParStatut(
                    StatutExamen.BROUILLON, Pageable.unpaged());

            assertThat(result.getContent()).isEmpty();
            verify(examenRepository, never()).findByStatut(any(), any(Pageable.class));
            verify(examenRepository, never()).findByMatiereIdInAndStatut(any(), any(), any(Pageable.class));
        }
    }

    // TROUVER PAR ID

    @Nested
    @DisplayName("trouverParId()")
    class TrouverParId {

        @Test
        @DisplayName("Doit retourner l'examen si trouvé")
        void trouverParId_devraitRetournerExamen() {
            when(examenRepository.findByIdWithStations(1L))
                    .thenReturn(Optional.of(examenBrouillon));

            ExamenResponse result = examenService.trouverParId(1L);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si introuvable")
        void trouverParId_devraitLeverExceptionSiIntrouvable() {
            when(examenRepository.findByIdWithStations(99L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> examenService.trouverParId(99L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("99");
        }

        @Test
        @DisplayName("Les stations embarquées doivent porter leurs evaluateurIds")
        void trouverParId_stationsEmbarquees_devraientPorterEvaluateurIds() {
            Station station = Station.builder()
                    .id(10L)
                    .nom("Station 1")
                    .ordre(1)
                    .examen(examenBrouillon)
                    .evaluateurIds(List.of(3L, 7L))
                    .build();
            examenBrouillon.setStations(List.of(station));
            when(examenRepository.findByIdWithStations(1L))
                    .thenReturn(Optional.of(examenBrouillon));

            ExamenResponse result = examenService.trouverParId(1L);

            assertThat(result.getStations()).hasSize(1);
            assertThat(result.getStations().get(0).getEvaluateurIds())
                    .containsExactly(3L, 7L);
        }
    }

    // MODIFIER

    @Nested
    @DisplayName("modifier()")
    class Modifier {

        @Test
        @DisplayName("Doit modifier l'examen si statut BROUILLON")
        void modifier_devraitModifierSiStatutBrouillon() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));
            when(examenRepository.save(any())).thenReturn(examenBrouillon);

            examenRequest.setNom("Nouveau nom");
            ExamenResponse result = examenService.modifier(1L, examenRequest);

            assertThat(result).isNotNull();
            verify(examenRepository).save(any(Examen.class));
        }

        @Test
        @DisplayName("Doit lever BusinessException si examen non en BROUILLON")
        void modifier_devraitLeverExceptionSiNonBrouillon() {
            examenBrouillon.setStatut(StatutExamen.EN_COURS);
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));

            assertThatThrownBy(() -> examenService.modifier(1L, examenRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("BROUILLON");
        }
    }

    // CHANGER STATUT

    @Nested
    @DisplayName("changerStatut()")
    class ChangerStatut {

        @Test
        @DisplayName("Transition BROUILLON → CONFIGURE doit réussir")
        void changerStatut_brouillonVersConfigure_doitReussir() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));
            when(examenRepository.save(any())).thenReturn(examenBrouillon);

            ExamenResponse result = examenService.changerStatut(1L, StatutExamen.CONFIGURE);

            assertThat(result).isNotNull();
            verify(examenRepository).save(any());
        }

        @Test
        @DisplayName("Transition BROUILLON → EN_COURS doit lever BusinessException")
        void changerStatut_brouillonVersEnCours_doitEchouer() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));

            assertThatThrownBy(() -> examenService.changerStatut(1L, StatutExamen.EN_COURS))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("invalide");
        }

        @Test
        @DisplayName("Transition ARCHIVE → tout doit lever BusinessException")
        void changerStatut_archive_doitToujoursEchouer() {
            examenBrouillon.setStatut(StatutExamen.ARCHIVE);
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));

            assertThatThrownBy(() -> examenService.changerStatut(1L, StatutExamen.BROUILLON))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // SUPPRIMER

    @Nested
    @DisplayName("supprimer()")
    class Supprimer {

        @Test
        @DisplayName("Doit supprimer si statut BROUILLON")
        void supprimer_devraitSupprimerSiBrouillon() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));

            examenService.supprimer(1L);

            verify(examenRepository).delete(examenBrouillon);
        }

        @Test
        @DisplayName("Doit lever BusinessException si examen EN_COURS")
        void supprimer_devraitLeverExceptionSiEnCours() {
            examenBrouillon.setStatut(StatutExamen.EN_COURS);
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));

            assertThatThrownBy(() -> examenService.supprimer(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("EN_COURS");
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si examen introuvable")
        void supprimer_devraitLeverExceptionSiIntrouvable() {
            when(examenRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> examenService.supprimer(99L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // IMPORT PDF

    @Nested
    @DisplayName("importerPdf()")
    class ImporterPdf {

        @Test
        @DisplayName("Doit lever BusinessException si fichier vide")
        void importerPdf_devraitLeverExceptionSiFichierVide() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));
            MockMultipartFile fichierVide = new MockMultipartFile(
                    "fichier", "test.pdf", "application/pdf", new byte[0]);

            assertThatThrownBy(() -> examenService.importerPdf(1L, fichierVide))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("vide");
        }

        @Test
        @DisplayName("Doit lever BusinessException si fichier non PDF")
        void importerPdf_devraitLeverExceptionSiNonPdf() {
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));
            MockMultipartFile fichierWord = new MockMultipartFile(
                    "fichier", "test.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "contenu".getBytes());

            assertThatThrownBy(() -> examenService.importerPdf(1L, fichierWord))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("PDF");
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si PDF demandé mais absent")
        void obtenirCheminPdf_devraitLeverExceptionSiAbsent() {
            examenBrouillon.setPdfSujetPath(null);
            when(examenRepository.findById(1L)).thenReturn(Optional.of(examenBrouillon));

            assertThatThrownBy(() -> examenService.obtenirCheminPdf(1L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
