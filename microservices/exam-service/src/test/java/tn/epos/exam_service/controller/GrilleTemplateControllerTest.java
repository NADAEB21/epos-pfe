package tn.epos.exam_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.exam_service.config.TestSecurityConfig;
import tn.epos.exam_service.controllers.GrilleTemplateController;
import tn.epos.exam_service.dto.request.GrilleTemplateRequest;
import tn.epos.exam_service.dto.request.ItemRequest;
import tn.epos.exam_service.dto.response.ExamenExportResponse;
import tn.epos.exam_service.dto.response.GrilleTemplateResponse;
import tn.epos.exam_service.dto.response.ItemResponse;
import tn.epos.exam_service.enums.TypeItem;
import tn.epos.common.exception.BusinessException;
import tn.epos.exam_service.exception.GlobalExceptionHandler;
import tn.epos.common.exception.ResourceNotFoundException;
import tn.epos.exam_service.services.GrilleTemplateService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {GrilleTemplateController.class, GlobalExceptionHandler.class})
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(TestSecurityConfig.class)
@DisplayName("GrilleTemplateController - Tests unitaires")
class GrilleTemplateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GrilleTemplateService templateService;

    private ObjectMapper objectMapper;
    private GrilleTemplateResponse templateResponse;
    private GrilleTemplateRequest templateRequest;

    @BeforeEach
    void setUp() {

        ItemResponse item1 = new ItemResponse();
        item1.setId(1L);
        item1.setLibelle("Choix de l'indicateur coloré");
        item1.setType(TypeItem.BINAIRE);
        item1.setPonderation(2.0);
        item1.setOrdre(1);

        ItemResponse item2 = new ItemResponse();
        item2.setId(2L);
        item2.setLibelle("Calcul de la masse");
        item2.setType(TypeItem.NUMERIQUE);
        item2.setPonderation(6.0);
        item2.setValeurMax(6.0);
        item2.setOrdre(2);

        templateResponse = new GrilleTemplateResponse();
        templateResponse.setId(1L);
        templateResponse.setNom("Template Station 3");
        templateResponse.setNoteMax(20.0);
        templateResponse.setNombreItems(2);
        templateResponse.setSommePonderations(8.0);
        templateResponse.setItems(List.of(item1, item2));

        ItemRequest itemReq = new ItemRequest();
        itemReq.setLibelle("Choix de l'indicateur coloré");
        itemReq.setType(TypeItem.BINAIRE);
        itemReq.setPonderation(2.0);

        templateRequest = new GrilleTemplateRequest();
        templateRequest.setNom("Template Station 3");
        templateRequest.setNoteMax(20.0);
        templateRequest.setItems(List.of(itemReq));

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ================================================================
    // POST /api/templates/grilles
    // ================================================================

    @Nested
    @DisplayName("POST /api/templates/grilles")
    class Creer {

        @Test
        @DisplayName("201 - Template créé manuellement")
        void creer_devraitRetourner201() throws Exception {
            when(templateService.creer(any(GrilleTemplateRequest.class)))
                    .thenReturn(templateResponse);

            mockMvc.perform(post("/api/templates/grilles")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.nom").value("Template Station 3"))
                    .andExpect(jsonPath("$.data.noteMax").value(20.0))
                    .andExpect(jsonPath("$.data.nombreItems").value(2));

            verify(templateService, times(1)).creer(any());
        }

        @Test
        @DisplayName("400 - Nom vide")
        void creer_nomVide_devraitRetourner400() throws Exception {
            templateRequest.setNom("");

            mockMvc.perform(post("/api/templates/grilles")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 - Nom déjà existant")
        void creer_nomDuplique_devraitRetourner400() throws Exception {
            when(templateService.creer(any()))
                    .thenThrow(new BusinessException("Un template nommé 'Template Station 3' existe déjà"));

            mockMvc.perform(post("/api/templates/grilles")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ================================================================
    // POST /api/grilles/{grilleId}/templates
    // ================================================================

    @Nested
    @DisplayName("POST /api/grilles/{grilleId}/templates")
    class SauvegarderDepuisGrille {

        @Test
        @DisplayName("201 - Template sauvegardé depuis une grille existante")
        void sauvegarder_devraitRetourner201() throws Exception {
            when(templateService.sauvegarderDepuisGrille(eq(1L), eq("Template Station 3")))
                    .thenReturn(templateResponse);

            mockMvc.perform(post("/api/grilles/1/templates")
                            .param("nom", "Template Station 3"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.nom").value("Template Station 3"))
                    .andExpect(jsonPath("$.data.nombreItems").value(2));

            verify(templateService, times(1)).sauvegarderDepuisGrille(1L, "Template Station 3");
        }

        @Test
        @DisplayName("400 - Nom de template déjà utilisé")
        void sauvegarder_nomDuplique_devraitRetourner400() throws Exception {
            when(templateService.sauvegarderDepuisGrille(eq(1L), any()))
                    .thenThrow(new BusinessException("Un template nommé 'Template Station 3' existe déjà"));

            mockMvc.perform(post("/api/grilles/1/templates")
                            .param("nom", "Template Station 3"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("404 - Grille introuvable")
        void sauvegarder_grilleIntrouvable_devraitRetourner404() throws Exception {
            when(templateService.sauvegarderDepuisGrille(eq(99L), any()))
                    .thenThrow(new ResourceNotFoundException("Grille", 99L));

            mockMvc.perform(post("/api/grilles/99/templates")
                            .param("nom", "Template X"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ================================================================
    // GET /api/templates/grilles
    // ================================================================

    @Nested
    @DisplayName("GET /api/templates/grilles")
    class Lister {

        @Test
        @DisplayName("200 - Liste tous les templates")
        void lister_devraitRetourner200() throws Exception {
            when(templateService.listerTous()).thenReturn(List.of(templateResponse));

            mockMvc.perform(get("/api/templates/grilles"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].nom").value("Template Station 3"))
                    .andExpect(jsonPath("$.data[0].noteMax").value(20.0));

            verify(templateService, times(1)).listerTous();
        }

        @Test
        @DisplayName("200 - Liste vide si aucun template")
        void lister_vide_devraitRetourner200() throws Exception {
            when(templateService.listerTous()).thenReturn(List.of());

            mockMvc.perform(get("/api/templates/grilles"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    // ================================================================
    // GET /api/templates/grilles/{id}
    // ================================================================

    @Nested
    @DisplayName("GET /api/templates/grilles/{id}")
    class TrouverParId {

        @Test
        @DisplayName("200 - Template trouvé avec ses items")
        void trouverParId_devraitRetourner200() throws Exception {
            when(templateService.trouverParId(1L)).thenReturn(templateResponse);

            mockMvc.perform(get("/api/templates/grilles/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.nom").value("Template Station 3"))
                    .andExpect(jsonPath("$.data.items").isArray())
                    .andExpect(jsonPath("$.data.items[0].type").value("BINAIRE"));
        }

        @Test
        @DisplayName("404 - Template introuvable")
        void trouverParId_introuvable_devraitRetourner404() throws Exception {
            when(templateService.trouverParId(99L))
                    .thenThrow(new ResourceNotFoundException("Template", 99L));

            mockMvc.perform(get("/api/templates/grilles/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ================================================================
    // DELETE /api/templates/grilles/{id}
    // ================================================================

    @Nested
    @DisplayName("DELETE /api/templates/grilles/{id}")
    class Supprimer {

        @Test
        @DisplayName("200 - Template supprimé")
        void supprimer_devraitRetourner200() throws Exception {
            doNothing().when(templateService).supprimer(1L);

            mockMvc.perform(delete("/api/templates/grilles/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Template supprimé"));

            verify(templateService, times(1)).supprimer(1L);
        }

        @Test
        @DisplayName("404 - Template introuvable")
        void supprimer_introuvable_devraitRetourner404() throws Exception {
            doThrow(new ResourceNotFoundException("Template", 99L))
                    .when(templateService).supprimer(99L);

            mockMvc.perform(delete("/api/templates/grilles/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ================================================================
    // POST /api/templates/grilles/{templateId}/appliquer/stations/{stationId}
    // ================================================================

    @Nested
    @DisplayName("POST /api/templates/grilles/{templateId}/appliquer/stations/{stationId}")
    class AppliquerSurStation {

        @Test
        @DisplayName("200 - Template appliqué avec succès")
        void appliquer_devraitRetourner200() throws Exception {
            doNothing().when(templateService).appliquerSurStation(1L, 2L);

            mockMvc.perform(post("/api/templates/grilles/1/appliquer/stations/2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Template appliqué avec succès"));

            verify(templateService, times(1)).appliquerSurStation(1L, 2L);
        }

        @Test
        @DisplayName("400 - Examen non modifiable")
        void appliquer_examenVerrouille_devraitRetourner400() throws Exception {
            doThrow(new BusinessException("Impossible d'appliquer un template : l'examen est au statut EN_COURS"))
                    .when(templateService).appliquerSurStation(1L, 2L);

            mockMvc.perform(post("/api/templates/grilles/1/appliquer/stations/2"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("404 - Template introuvable")
        void appliquer_templateIntrouvable_devraitRetourner404() throws Exception {
            doThrow(new ResourceNotFoundException("Template", 99L))
                    .when(templateService).appliquerSurStation(99L, 2L);

            mockMvc.perform(post("/api/templates/grilles/99/appliquer/stations/2"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("404 - Station introuvable")
        void appliquer_stationIntrouvable_devraitRetourner404() throws Exception {
            doThrow(new ResourceNotFoundException("Station", 99L))
                    .when(templateService).appliquerSurStation(1L, 99L);

            mockMvc.perform(post("/api/templates/grilles/1/appliquer/stations/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ================================================================
    // POST /api/examens/{examenId}/dupliquer
    // ================================================================

    @Nested
    @DisplayName("POST /api/examens/{examenId}/dupliquer")
    class DupliquerExamen {

        @Test
        @DisplayName("201 - Examen dupliqué, retourne le nouvel ID")
        void dupliquer_devraitRetourner201() throws Exception {
            when(templateService.dupliquerExamen(eq(1L), eq("Copie Examen Chimie")))
                    .thenReturn(42L);

            mockMvc.perform(post("/api/examens/1/dupliquer")
                            .param("nouveauNom", "Copie Examen Chimie"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").value(42));

            verify(templateService, times(1)).dupliquerExamen(1L, "Copie Examen Chimie");
        }

        @Test
        @DisplayName("404 - Examen source introuvable")
        void dupliquer_examenIntrouvable_devraitRetourner404() throws Exception {
            when(templateService.dupliquerExamen(eq(99L), any()))
                    .thenThrow(new ResourceNotFoundException("Examen", 99L));

            mockMvc.perform(post("/api/examens/99/dupliquer")
                            .param("nouveauNom", "Copie X"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ================================================================
    // GET /api/examens/{examenId}/export
    // ================================================================

    @Nested
    @DisplayName("GET /api/examens/{examenId}/export")
    class ExporterExamen {

        @Test
        @DisplayName("200 - Export JSON téléchargeable")
        void exporter_devraitRetourner200AvecJson() throws Exception {
            ExamenExportResponse export = new ExamenExportResponse();
            export.setNom("Examen Chimie");
            export.setMatiereId(1L);
            export.setDateExamen(LocalDate.of(2024, 6, 15));
            export.setDureeStationMin(15);
            export.setNbEtudiantsParStation(4);
            export.setStations(new ArrayList<>());

            when(templateService.exporterExamen(1L)).thenReturn(export);

            mockMvc.perform(get("/api/examens/1/export"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"examen_1_export.json\""))
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON));

            verify(templateService, times(1)).exporterExamen(1L);
        }

        @Test
        @DisplayName("404 - Examen introuvable pour export")
        void exporter_examenIntrouvable_devraitRetourner404() throws Exception {
            when(templateService.exporterExamen(99L))
                    .thenThrow(new ResourceNotFoundException("Examen", 99L));

            mockMvc.perform(get("/api/examens/99/export"))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // POST /api/stations/{stationId}/grille/import
    // ================================================================

    @Nested
    @DisplayName("POST /api/stations/{stationId}/grille/import")
    class ImporterGrilleJson {

        @Test
        @DisplayName("200 - Import JSON réussi")
        void importer_devraitRetourner200() throws Exception {
            doNothing().when(templateService).importerGrilleJson(eq(1L), anyString());

            String grilleJson = """
                    {
                      "nom": "Grille importée",
                      "noteMax": 20.0,
                      "items": []
                    }
                    """;

            MockMultipartFile fichier = new MockMultipartFile(
                    "fichier", "grille.json",
                    "application/json", grilleJson.getBytes());

            mockMvc.perform(multipart("/api/stations/1/grille/import").file(fichier))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Grille importée avec succès"));

            verify(templateService, times(1)).importerGrilleJson(eq(1L), anyString());
        }

        @Test
        @DisplayName("400 - Fichier vide")
        void importer_fichierVide_devraitRetourner400() throws Exception {
            MockMultipartFile fichierVide = new MockMultipartFile(
                    "fichier", "grille.json", "application/json", new byte[0]);

            mockMvc.perform(multipart("/api/stations/1/grille/import").file(fichierVide))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 - JSON invalide")
        void importer_jsonInvalide_devraitRetourner400() throws Exception {
            doThrow(new BusinessException("Format JSON invalide"))
                    .when(templateService).importerGrilleJson(eq(1L), anyString());

            MockMultipartFile fichier = new MockMultipartFile(
                    "fichier", "grille.json",
                    "application/json", "contenu invalide".getBytes());

            mockMvc.perform(multipart("/api/stations/1/grille/import").file(fichier))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("400 - Examen non modifiable")
        void importer_examenVerrouille_devraitRetourner400() throws Exception {
            doThrow(new BusinessException("Impossible d'importer : l'examen est au statut EN_COURS"))
                    .when(templateService).importerGrilleJson(eq(1L), anyString());

            MockMultipartFile fichier = new MockMultipartFile(
                    "fichier", "grille.json",
                    "application/json", "{\"nom\":\"test\"}".getBytes());

            mockMvc.perform(multipart("/api/stations/1/grille/import").file(fichier))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("404 - Station introuvable")
        void importer_stationIntrouvable_devraitRetourner404() throws Exception {
            doThrow(new ResourceNotFoundException("Station", 99L))
                    .when(templateService).importerGrilleJson(eq(99L), anyString());

            MockMultipartFile fichier = new MockMultipartFile(
                    "fichier", "grille.json",
                    "application/json", "{\"nom\":\"test\"}".getBytes());

            mockMvc.perform(multipart("/api/stations/99/grille/import").file(fichier))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }
}
