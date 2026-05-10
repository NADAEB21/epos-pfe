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
// import org.springframework.test.context.ActiveProfiles;
import tn.epos.exam_service.dto.request.ExamenRequest;
import tn.epos.exam_service.dto.response.ExamenResponse;
import tn.epos.exam_service.entities.Examen;
import tn.epos.exam_service.enums.StatutExamen;
import tn.epos.exam_service.exception.BusinessException;
import tn.epos.exam_service.exception.ResourceNotFoundException;
import tn.epos.exam_service.repositories.ExamenRepository;
import tn.epos.exam_service.services.impl.ExamenServiceImpl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import static org.mockito.ArgumentMatchers.eq;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExamenService - Tests unitaires")
class ExamenServiceImplTest {

    @Mock
    private ExamenRepository examenRepository;

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
        examenRequest.setMatiere("Chimie");
        examenRequest.setDateExamen(LocalDate.of(2024, 6, 15));
        examenRequest.setDureeStationMin(15);
        examenRequest.setNbEtudiantsParStation(4);

        examenBrouillon = Examen.builder()
                .id(1L)
                .nom("Examen Test")
                .matiere("Chimie")
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
        @DisplayName("Doit retourner la liste de tous les examens")
        void listerTous_devraitRetournerListe() {
            Page<Examen> pageEntite = new PageImpl<>(List.of(examenBrouillon));
            when(examenRepository.findAll(any(Pageable.class))).thenReturn(pageEntite);

            Page<ExamenResponse> result = examenService.listerTous(Pageable.unpaged());

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getNom()).isEqualTo("Examen Test");
        }

        @Test
        @DisplayName("Doit retourner liste vide si aucun examen")
        void listerTous_devraitRetournerListeVide() {
            Page<Examen> pageVide = new PageImpl<>(List.of());
            when(examenRepository.findAll(any(Pageable.class))).thenReturn(pageVide);

            Page<ExamenResponse> result = examenService.listerTous(Pageable.unpaged());

            assertThat(result.getContent()).isEmpty();
        }

        @Test
        @DisplayName("Doit filtrer par statut")
        void listerParStatut_devraitFiltrerParStatut() {
            Page<Examen> page = new PageImpl<>(List.of(examenBrouillon));
            when(examenRepository.findByStatut(eq(StatutExamen.BROUILLON), any(Pageable.class)))
                    .thenReturn(page);

            Page<ExamenResponse> result = examenService.listerParStatut(
                    StatutExamen.BROUILLON, Pageable.unpaged());

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getStatut()).isEqualTo(StatutExamen.BROUILLON);
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
