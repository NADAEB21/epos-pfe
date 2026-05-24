package tn.epos.exam_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import tn.epos.exam_service.dto.request.GrilleTemplateRequest;
import tn.epos.exam_service.dto.request.ItemRequest;
import tn.epos.exam_service.dto.response.ExamenExportResponse;
import tn.epos.exam_service.dto.response.GrilleTemplateResponse;
import tn.epos.exam_service.entities.*;
import tn.epos.exam_service.enums.StatutExamen;
import tn.epos.exam_service.enums.TypeItem;
import tn.epos.exam_service.enums.TypeStation;
import tn.epos.exam_service.exception.BusinessException;
import tn.epos.exam_service.exception.ResourceNotFoundException;
import tn.epos.exam_service.repositories.*;
import tn.epos.exam_service.services.impl.GrilleTemplateServiceImpl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GrilleTemplateService - Tests unitaires")
class GrilleTemplateServiceImplTest {

    @Mock private GrilleTemplateRepository templateRepository;
    @Mock private GrilleEvaluationRepository grilleRepository;
    @Mock private StationRepository stationRepository;
    @Mock private ExamenRepository examenRepository;
    @Mock private MatiereAccessChecker matiereAccessChecker;
    //@Mock private ObjectMapper objectMapper;

    @InjectMocks
    private final ObjectMapper objectMapper = new ObjectMapper();
    private GrilleTemplateServiceImpl templateService;

    private Examen examenBrouillon;
    private Station station;
    private GrilleEvaluation grille;
    private GrilleTemplate template;
    private ItemEvaluation itemBinaire;
    private ItemTemplate itemTemplate;

    @BeforeEach
    void setUp() {
        templateService = new GrilleTemplateServiceImpl(
                templateRepository,
                grilleRepository,
                stationRepository,
                examenRepository,
                objectMapper,
                matiereAccessChecker
        );

        examenBrouillon = Examen.builder()
                .id(1L).nom("Examen Test").matiereId(1L)
                .dateExamen(LocalDate.now())
                .statut(StatutExamen.BROUILLON)
                .build();

        station = Station.builder()
                .id(1L).nom("Station 3").type(TypeStation.PRATIQUE)
                .ordre(1).examen(examenBrouillon).build();

        itemBinaire = ItemEvaluation.builder()
                .id(1L).libelle("Choix de l'indicateur")
                .type(TypeItem.BINAIRE).ponderation(2.0).ordre(1)
                .build();

        grille = GrilleEvaluation.builder()
                .id(1L).nom("Grille Station 3").noteMax(20.0)
                .station(station).items(new ArrayList<>(List.of(itemBinaire)))
                .build();
        itemBinaire.setGrille(grille);

        itemTemplate = ItemTemplate.builder()
                .id(1L).libelle("Choix de l'indicateur")
                .type(TypeItem.BINAIRE).ponderation(2.0).ordre(1)
                .build();

        template = GrilleTemplate.builder()
                .id(1L).nom("Template Station 3").noteMax(20.0)
                .items(new ArrayList<>(List.of(itemTemplate)))
                .build();
        itemTemplate.setTemplate(template);
    }

    // ================================================================
    // BF2.4 — sauvegarderDepuisGrille()
    // ================================================================

    @Nested
    @DisplayName("sauvegarderDepuisGrille()")
    class SauvegarderDepuisGrille {

        @Test
        @DisplayName("Doit créer un template avec les items de la grille")
        void sauvegarder_devraitCreerTemplate() {
            when(grilleRepository.findByIdWithItems(1L)).thenReturn(Optional.of(grille));
            when(templateRepository.existsByNom("Template Station 3")).thenReturn(false);
            when(templateRepository.save(any(GrilleTemplate.class))).thenReturn(template);

            GrilleTemplateResponse result =
                    templateService.sauvegarderDepuisGrille(1L, "Template Station 3");

            assertThat(result).isNotNull();
            assertThat(result.getNom()).isEqualTo("Template Station 3");
            verify(templateRepository).save(any(GrilleTemplate.class));
        }

        @Test
        @DisplayName("Doit lever BusinessException si nom déjà utilisé")
        void sauvegarder_nomDuplique_devraitEchouer() {
            when(grilleRepository.findByIdWithItems(1L)).thenReturn(Optional.of(grille));
            when(templateRepository.existsByNom("Template Station 3")).thenReturn(true);

            assertThatThrownBy(() ->
                    templateService.sauvegarderDepuisGrille(1L, "Template Station 3"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("existe déjà");
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si grille introuvable")
        void sauvegarder_grilleIntrouvable_devraitEchouer() {
            when(grilleRepository.findByIdWithItems(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    templateService.sauvegarderDepuisGrille(99L, "Template X"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Doit copier tous les items de la grille dans le template")
        void sauvegarder_doitCopierLesItems() {
            ItemEvaluation item2 = ItemEvaluation.builder()
                    .id(2L).libelle("Calcul masse").type(TypeItem.NUMERIQUE)
                    .ponderation(6.0).valeurMax(6.0).ordre(2).grille(grille).build();
            grille.getItems().add(item2);

            when(grilleRepository.findByIdWithItems(1L)).thenReturn(Optional.of(grille));
            when(templateRepository.existsByNom(any())).thenReturn(false);
            when(templateRepository.save(any())).thenAnswer(inv -> {
                GrilleTemplate saved = inv.getArgument(0);
                assertThat(saved.getItems()).hasSize(2);
                return template;
            });

            templateService.sauvegarderDepuisGrille(1L, "Template Complet");

            verify(templateRepository).save(any());
        }
    }

    // ================================================================
    // BF2.4 — creer()
    // ================================================================

    @Nested
    @DisplayName("creer()")
    class Creer {

        @Test
        @DisplayName("Doit créer un template manuellement avec items")
        void creer_devraitCreerTemplate() {
            ItemRequest itemReq = new ItemRequest();
            itemReq.setLibelle("Choix indicateur");
            itemReq.setType(TypeItem.BINAIRE);
            itemReq.setPonderation(2.0);

            GrilleTemplateRequest request = new GrilleTemplateRequest();
            request.setNom("Nouveau Template");
            request.setNoteMax(20.0);
            request.setItems(List.of(itemReq));

            when(templateRepository.existsByNom("Nouveau Template")).thenReturn(false);
            when(templateRepository.save(any())).thenReturn(template);

            GrilleTemplateResponse result = templateService.creer(request);

            assertThat(result).isNotNull();
            verify(templateRepository).save(any());
        }

        @Test
        @DisplayName("Doit lever BusinessException si nom déjà existant")
        void creer_nomDuplique_devraitEchouer() {
            GrilleTemplateRequest request = new GrilleTemplateRequest();
            request.setNom("Template Station 3");
            request.setNoteMax(20.0);

            when(templateRepository.existsByNom("Template Station 3")).thenReturn(true);

            assertThatThrownBy(() -> templateService.creer(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("existe déjà");
        }
    }

    // ================================================================
    // BF2.4 — trouverParId() / listerTous()
    // ================================================================

    @Nested
    @DisplayName("trouverParId() et listerTous()")
    class Consulter {

        @Test
        @DisplayName("Doit retourner le template avec ses items")
        void trouverParId_devraitRetournerTemplate() {
            when(templateRepository.findByIdWithItems(1L)).thenReturn(Optional.of(template));

            GrilleTemplateResponse result = templateService.trouverParId(1L);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getNombreItems()).isEqualTo(1);
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si template introuvable")
        void trouverParId_introuvable_devraitEchouer() {
            when(templateRepository.findByIdWithItems(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> templateService.trouverParId(99L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Doit retourner tous les templates")
        void listerTous_devraitRetournerListe() {
            when(templateRepository.findAllWithItems()).thenReturn(List.of(template));

            List<GrilleTemplateResponse> result = templateService.listerTous();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getNom()).isEqualTo("Template Station 3");
        }

        @Test
        @DisplayName("Doit retourner liste vide si aucun template")
        void listerTous_vide_devraitRetournerListeVide() {
            when(templateRepository.findAllWithItems()).thenReturn(List.of());

            List<GrilleTemplateResponse> result = templateService.listerTous();

            assertThat(result).isEmpty();
        }
    }

    // ================================================================
    // BF2.4 — supprimer()
    // ================================================================

    @Nested
    @DisplayName("supprimer()")
    class Supprimer {

        @Test
        @DisplayName("Doit supprimer le template")
        void supprimer_devraitSupprimer() {
            when(templateRepository.findById(1L)).thenReturn(Optional.of(template));

            templateService.supprimer(1L);

            verify(templateRepository).delete(template);
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si template introuvable")
        void supprimer_introuvable_devraitEchouer() {
            when(templateRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> templateService.supprimer(99L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ================================================================
    // BF2.4 — appliquerSurStation()
    // ================================================================

    @Nested
    @DisplayName("appliquerSurStation()")
    class AppliquerSurStation {

        @Test
        @DisplayName("Doit appliquer le template et créer une nouvelle grille")
        void appliquer_devraitCreerGrille() {
            when(templateRepository.findByIdWithItems(1L)).thenReturn(Optional.of(template));
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(grilleRepository.findByStationId(1L)).thenReturn(Optional.empty());
            when(grilleRepository.save(any())).thenReturn(grille);

            templateService.appliquerSurStation(1L, 1L);

            verify(grilleRepository).save(any(GrilleEvaluation.class));
        }

        @Test
        @DisplayName("Doit supprimer l'ancienne grille avant d'appliquer le template")
        void appliquer_doitSupprimerAncienneGrille() {
            when(templateRepository.findByIdWithItems(1L)).thenReturn(Optional.of(template));
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(grilleRepository.findByStationId(1L)).thenReturn(Optional.of(grille));
            when(grilleRepository.save(any())).thenReturn(grille);

            templateService.appliquerSurStation(1L, 1L);

            verify(grilleRepository).delete(grille);
            verify(grilleRepository).save(any(GrilleEvaluation.class));
        }

        @Test
        @DisplayName("Doit lever BusinessException si examen non modifiable")
        void appliquer_examenVerrouille_devraitEchouer() {
            examenBrouillon.setStatut(StatutExamen.EN_COURS);
            when(templateRepository.findByIdWithItems(1L)).thenReturn(Optional.of(template));
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));

            assertThatThrownBy(() -> templateService.appliquerSurStation(1L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("EN_COURS");
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si template introuvable")
        void appliquer_templateIntrouvable_devraitEchouer() {
            when(templateRepository.findByIdWithItems(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> templateService.appliquerSurStation(99L, 1L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si station introuvable")
        void appliquer_stationIntrouvable_devraitEchouer() {
            when(templateRepository.findByIdWithItems(1L)).thenReturn(Optional.of(template));
            when(stationRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> templateService.appliquerSurStation(1L, 99L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ================================================================
    // BF2.5 — exporterExamen()
    // ================================================================

    @Nested
    @DisplayName("exporterExamen()")
    class ExporterExamen {

        @Test
        @DisplayName("Doit exporter l'examen avec ses stations et grilles")
        void exporter_devraitRetournerExport() {
            station.setGrille(grille);
            examenBrouillon.setStations(new ArrayList<>(List.of(station)));

            when(examenRepository.findByIdWithStations(1L))
                    .thenReturn(Optional.of(examenBrouillon));
            when(grilleRepository.findByStationIdWithItems(1L))
                    .thenReturn(Optional.of(grille));

            ExamenExportResponse result = templateService.exporterExamen(1L);

            assertThat(result).isNotNull();
            assertThat(result.getNom()).isEqualTo("Examen Test");
            assertThat(result.getMatiereId()).isEqualTo(1L);
            assertThat(result.getStations()).hasSize(1);
            assertThat(result.getStations().get(0).getGrille()).isNotNull();
            assertThat(result.getStations().get(0).getGrille().getNom())
                    .isEqualTo("Grille Station 3");
        }

        @Test
        @DisplayName("Doit exporter les stations sans grille")
        void exporter_stationsSansGrille_devraitReussir() {
            examenBrouillon.setStations(new ArrayList<>(List.of(station)));

            when(examenRepository.findByIdWithStations(1L))
                    .thenReturn(Optional.of(examenBrouillon));
            when(grilleRepository.findByStationIdWithItems(1L))
                    .thenReturn(Optional.empty());

            ExamenExportResponse result = templateService.exporterExamen(1L);

            assertThat(result.getStations()).hasSize(1);
            assertThat(result.getStations().get(0).getGrille()).isNull();
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si examen introuvable")
        void exporter_examenIntrouvable_devraitEchouer() {
            when(examenRepository.findByIdWithStations(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> templateService.exporterExamen(99L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ================================================================
    // BF2.4 — dupliquerExamen()
    // ================================================================

    @Nested
    @DisplayName("dupliquerExamen()")
    class DupliquerExamen {

        @Test
        @DisplayName("Doit dupliquer l'examen avec stations et grilles")
        void dupliquer_devraitCreerCopieComplete() {
            examenBrouillon.setStations(new ArrayList<>(List.of(station)));

            Examen copie = Examen.builder().id(42L).nom("Copie Examen")
                    .matiereId(1L).statut(StatutExamen.BROUILLON)
                    .dateExamen(LocalDate.now()).build();

            when(examenRepository.findByIdWithStations(1L))
                    .thenReturn(Optional.of(examenBrouillon));
            when(examenRepository.save(any(Examen.class))).thenReturn(copie);
            when(stationRepository.save(any(Station.class))).thenReturn(station);
            when(grilleRepository.findByStationIdWithItems(1L))
                    .thenReturn(Optional.of(grille));
            when(grilleRepository.save(any(GrilleEvaluation.class))).thenReturn(grille);

            Long nouvelId = templateService.dupliquerExamen(1L, "Copie Examen");

            assertThat(nouvelId).isEqualTo(42L);
            verify(examenRepository).save(any(Examen.class));
            verify(stationRepository).save(any(Station.class));
            verify(grilleRepository).save(any(GrilleEvaluation.class));
        }

        @Test
        @DisplayName("La copie doit toujours avoir le statut BROUILLON")
        void dupliquer_copieDoitEtreBrouillon() {
            examenBrouillon.setStatut(StatutExamen.CONFIGURE);
            examenBrouillon.setStations(new ArrayList<>());

            Examen copie = Examen.builder().id(42L).nom("Copie")
                    .statut(StatutExamen.BROUILLON).build();

            when(examenRepository.findByIdWithStations(1L))
                    .thenReturn(Optional.of(examenBrouillon));
            when(examenRepository.save(any(Examen.class))).thenAnswer(inv -> {
                Examen saved = inv.getArgument(0);
                assertThat(saved.getStatut()).isEqualTo(StatutExamen.BROUILLON);
                return copie;
            });

            templateService.dupliquerExamen(1L, "Copie");

            verify(examenRepository).save(any());
        }

        @Test
        @DisplayName("Doit dupliquer les stations sans grille")
        void dupliquer_stationsSansGrille_devraitReussir() {
            examenBrouillon.setStations(new ArrayList<>(List.of(station)));

            Examen copie = Examen.builder().id(42L).nom("Copie")
                    .statut(StatutExamen.BROUILLON).build();

            when(examenRepository.findByIdWithStations(1L))
                    .thenReturn(Optional.of(examenBrouillon));
            when(examenRepository.save(any())).thenReturn(copie);
            when(stationRepository.save(any())).thenReturn(station);
            when(grilleRepository.findByStationIdWithItems(1L))
                    .thenReturn(Optional.empty());

            Long nouvelId = templateService.dupliquerExamen(1L, "Copie");

            assertThat(nouvelId).isEqualTo(42L);
            verify(grilleRepository, never()).save(any());
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si examen source introuvable")
        void dupliquer_examenIntrouvable_devraitEchouer() {
            when(examenRepository.findByIdWithStations(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> templateService.dupliquerExamen(99L, "Copie"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ================================================================
    // BF2.5 — importerGrilleJson()
    // ================================================================

    @Nested
    @DisplayName("importerGrilleJson()")
    class ImporterGrilleJson {

        @Test
        @DisplayName("Doit importer une grille valide depuis JSON")
        void importer_devraitCreerGrille() {
            // JSON valide que le vrai ObjectMapper peut désérialiser
            String grilleJson = """
                {
                  "nom": "Grille importée",
                  "noteMax": 20.0,
                  "description": null,
                  "items": []
                }
                """;

            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(grilleRepository.findByStationId(1L)).thenReturn(Optional.empty());
            when(grilleRepository.save(any())).thenReturn(grille);

            templateService.importerGrilleJson(1L, grilleJson);

            verify(grilleRepository).save(any(GrilleEvaluation.class));
        }

        @Test
        @DisplayName("Doit importer une grille avec items depuis JSON")
        void importer_avecItems_devraitCreerGrilleAvecItems() {
            String grilleJson = """
                {
                  "nom": "Grille importée",
                  "noteMax": 20.0,
                  "description": "desc",
                  "items": [
                    {
                      "libelle": "Choix indicateur",
                      "type": "BINAIRE",
                      "ponderation": 2.0,
                      "valeurMax": null,
                      "categorie": null,
                      "ordre": 1
                    }
                  ]
                }
                """;

            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(grilleRepository.findByStationId(1L)).thenReturn(Optional.empty());
            when(grilleRepository.save(any())).thenReturn(grille);

            templateService.importerGrilleJson(1L, grilleJson);

            verify(grilleRepository).save(argThat(g ->
                    g.getItems().size() == 1 &&
                            g.getItems().get(0).getLibelle().equals("Choix indicateur")
            ));
        }

        @Test
        @DisplayName("Doit supprimer l'ancienne grille avant l'import")
        void importer_doitSupprimerAncienneGrille() {
            String grilleJson = """
                {
                  "nom": "Grille importée",
                  "noteMax": 20.0,
                  "items": []
                }
                """;

            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            when(grilleRepository.findByStationId(1L)).thenReturn(Optional.of(grille));
            when(grilleRepository.save(any())).thenReturn(grille);

            templateService.importerGrilleJson(1L, grilleJson);

            verify(grilleRepository).delete(grille);
            verify(grilleRepository).save(any());
        }

        @Test
        @DisplayName("Doit lever BusinessException si JSON invalide")
        void importer_jsonInvalide_devraitEchouer() {
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            // pas de stub pour grilleRepository : le parsing échoue avant

            assertThatThrownBy(() ->
                    templateService.importerGrilleJson(1L, "{ json invalide !!!"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("JSON invalide");
        }

        @Test
        @DisplayName("Doit lever BusinessException si examen non modifiable")
        void importer_examenVerrouille_devraitEchouer() {
            examenBrouillon.setStatut(StatutExamen.EN_COURS);
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));

            assertThatThrownBy(() ->
                    templateService.importerGrilleJson(1L, "{\"nom\":\"test\"}"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("EN_COURS");
        }

        @Test
        @DisplayName("Doit lever ResourceNotFoundException si station introuvable")
        void importer_stationIntrouvable_devraitEchouer() {
            when(stationRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    templateService.importerGrilleJson(99L, "{\"nom\":\"test\"}"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ================================================================
    // #96 — per-matiere authz: every endpoint that touches a station/exam
    // must reject an out-of-scope caller. Standalone template CRUD
    // (creer / supprimer) is locked to SUPER_ADMIN at the controller layer.
    // ================================================================

    @Nested
    @DisplayName("MatiereAccessChecker enforcement (#96)")
    class MatiereScopeEnforcement {

        @Test
        @DisplayName("sauvegarderDepuisGrille() rejects when caller is out of scope")
        void sauvegarder_outOfScope_devraitRefuser() {
            when(grilleRepository.findByIdWithItems(1L)).thenReturn(Optional.of(grille));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            assertThatThrownBy(() ->
                    templateService.sauvegarderDepuisGrille(1L, "Template X"))
                    .isInstanceOf(AccessDeniedException.class);
            verify(templateRepository, never()).save(any());
        }

        @Test
        @DisplayName("appliquerSurStation() rejects when caller is out of scope")
        void appliquer_outOfScope_devraitRefuser() {
            when(templateRepository.findByIdWithItems(1L)).thenReturn(Optional.of(template));
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            assertThatThrownBy(() -> templateService.appliquerSurStation(1L, 1L))
                    .isInstanceOf(AccessDeniedException.class);
            verify(grilleRepository, never()).save(any());
        }

        @Test
        @DisplayName("dupliquerExamen() rejects when caller is out of scope")
        void dupliquer_outOfScope_devraitRefuser() {
            when(examenRepository.findByIdWithStations(1L))
                    .thenReturn(Optional.of(examenBrouillon));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            assertThatThrownBy(() -> templateService.dupliquerExamen(1L, "Copie"))
                    .isInstanceOf(AccessDeniedException.class);
            verify(examenRepository, never()).save(any());
        }

        @Test
        @DisplayName("exporterExamen() rejects when caller is out of scope")
        void exporter_outOfScope_devraitRefuser() {
            when(examenRepository.findByIdWithStations(1L))
                    .thenReturn(Optional.of(examenBrouillon));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            assertThatThrownBy(() -> templateService.exporterExamen(1L))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("importerGrilleJson() rejects when caller is out of scope")
        void importer_outOfScope_devraitRefuser() {
            when(stationRepository.findById(1L)).thenReturn(Optional.of(station));
            doThrow(new AccessDeniedException("nope"))
                    .when(matiereAccessChecker).checkAccess(1L);

            assertThatThrownBy(() ->
                    templateService.importerGrilleJson(1L, "{\"nom\":\"test\"}"))
                    .isInstanceOf(AccessDeniedException.class);
            verify(grilleRepository, never()).save(any());
        }
    }
}
