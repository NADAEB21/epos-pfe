package tn.epos.exam_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.exam_service.controllers.ExamenController;
import tn.epos.exam_service.dto.request.ExamenRequest;
import tn.epos.exam_service.dto.response.ExamenResponse;
import tn.epos.exam_service.enums.StatutExamen;
import tn.epos.exam_service.exception.BusinessException;
import tn.epos.exam_service.exception.ResourceNotFoundException;
import tn.epos.exam_service.services.ExamenService;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ExamenController.class)
@ActiveProfiles("test")
@DisplayName("ExamenController - Tests unitaires")
class ExamenControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExamenService examenService;

    private ObjectMapper objectMapper;
    private ExamenResponse examenResponse;
    private ExamenRequest examenRequest;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        examenResponse = new ExamenResponse();
        examenResponse.setId(1L);
        examenResponse.setNom("Examen Test");
        examenResponse.setMatiere("Chimie");
        examenResponse.setDateExamen(LocalDate.of(2024, 6, 15));
        examenResponse.setStatut(StatutExamen.BROUILLON);
        examenResponse.setDureeStationMin(15);
        examenResponse.setNbEtudiantsParStation(4);

        examenRequest = new ExamenRequest();
        examenRequest.setNom("Examen Test");
        examenRequest.setMatiere("Chimie");
        examenRequest.setDateExamen(LocalDate.of(2024, 6, 15));
        examenRequest.setDureeStationMin(15);
        examenRequest.setNbEtudiantsParStation(4);
    }

    // ================================================================
    // POST /api/v1/examens
    // ================================================================

    @Nested
    @DisplayName("POST /api/v1/examens")
    class Creer {

        @Test
        @DisplayName("Doit retourner 201 avec l'examen créé")
        void creer_devraitRetourner201() throws Exception {
            when(examenService.creer(any(ExamenRequest.class))).thenReturn(examenResponse);

            mockMvc.perform(post("/api/v1/examens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(examenRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.nom").value("Examen Test"))
                    .andExpect(jsonPath("$.data.statut").value("BROUILLON"));
        }

        @Test
        @DisplayName("Doit retourner 400 si nom manquant")
        void creer_devraitRetourner400SiNomManquant() throws Exception {
            examenRequest.setNom("");

            mockMvc.perform(post("/api/v1/examens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(examenRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Doit retourner 400 si dateExamen manquante")
        void creer_devraitRetourner400SiDateManquante() throws Exception {
            examenRequest.setDateExamen(null);

            mockMvc.perform(post("/api/v1/examens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(examenRequest)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ================================================================
    // GET /api/v1/examens
    // ================================================================

    @Nested
    @DisplayName("GET /api/v1/examens")
    class Lister {

        @Test
        @DisplayName("Doit retourner 200 avec la liste des examens")
        void lister_devraitRetourner200() throws Exception {
            when(examenService.listerTous()).thenReturn(List.of(examenResponse));

            mockMvc.perform(get("/api/v1/examens"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].nom").value("Examen Test"));
        }

        @Test
        @DisplayName("Doit filtrer par statut si paramètre fourni")
        void lister_devraitFiltrerParStatut() throws Exception {
            when(examenService.listerParStatut(StatutExamen.BROUILLON))
                    .thenReturn(List.of(examenResponse));

            mockMvc.perform(get("/api/v1/examens").param("statut", "BROUILLON"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].statut").value("BROUILLON"));

            verify(examenService).listerParStatut(StatutExamen.BROUILLON);
            verify(examenService, never()).listerTous();
        }

        @Test
        @DisplayName("Doit retourner liste vide si aucun examen")
        void lister_devraitRetournerListeVide() throws Exception {
            when(examenService.listerTous()).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/examens"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    // ================================================================
    // GET /api/v1/examens/{id}
    // ================================================================

    @Nested
    @DisplayName("GET /api/v1/examens/{id}")
    class TrouverParId {

        @Test
        @DisplayName("Doit retourner 200 avec l'examen")
        void trouverParId_devraitRetourner200() throws Exception {
            when(examenService.trouverParId(1L)).thenReturn(examenResponse);

            mockMvc.perform(get("/api/v1/examens/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.nom").value("Examen Test"));
        }

        @Test
        @DisplayName("Doit retourner 404 si examen introuvable")
        void trouverParId_devraitRetourner404() throws Exception {
            when(examenService.trouverParId(99L))
                    .thenThrow(new ResourceNotFoundException("Examen", 99L));

            mockMvc.perform(get("/api/v1/examens/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ================================================================
    // PUT /api/v1/examens/{id}
    // ================================================================

    @Nested
    @DisplayName("PUT /api/v1/examens/{id}")
    class Modifier {

        @Test
        @DisplayName("Doit retourner 200 avec l'examen modifié")
        void modifier_devraitRetourner200() throws Exception {
            when(examenService.modifier(eq(1L), any(ExamenRequest.class)))
                    .thenReturn(examenResponse);

            mockMvc.perform(put("/api/v1/examens/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(examenRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("Doit retourner 400 si examen non modifiable")
        void modifier_devraitRetourner400SiNonModifiable() throws Exception {
            when(examenService.modifier(eq(1L), any()))
                    .thenThrow(new BusinessException("Statut invalide"));

            mockMvc.perform(put("/api/v1/examens/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(examenRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ================================================================
    // PATCH /api/v1/examens/{id}/statut
    // ================================================================

    @Nested
    @DisplayName("PATCH /api/v1/examens/{id}/statut")
    class ChangerStatut {

        @Test
        @DisplayName("Doit retourner 200 après changement de statut")
        void changerStatut_devraitRetourner200() throws Exception {
            examenResponse.setStatut(StatutExamen.CONFIGURE);
            when(examenService.changerStatut(1L, StatutExamen.CONFIGURE))
                    .thenReturn(examenResponse);

            mockMvc.perform(patch("/api/v1/examens/1/statut")
                            .param("statut", "CONFIGURE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.statut").value("CONFIGURE"));
        }

        @Test
        @DisplayName("Doit retourner 400 si transition invalide")
        void changerStatut_devraitRetourner400SiTransitionInvalide() throws Exception {
            when(examenService.changerStatut(1L, StatutExamen.EN_COURS))
                    .thenThrow(new BusinessException("Transition invalide"));

            mockMvc.perform(patch("/api/v1/examens/1/statut")
                            .param("statut", "EN_COURS"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ================================================================
    // DELETE /api/v1/examens/{id}
    // ================================================================

    @Nested
    @DisplayName("DELETE /api/v1/examens/{id}")
    class Supprimer {

        @Test
        @DisplayName("Doit retourner 200 après suppression")
        void supprimer_devraitRetourner200() throws Exception {
            doNothing().when(examenService).supprimer(1L);

            mockMvc.perform(delete("/api/v1/examens/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("Doit retourner 400 si suppression interdite")
        void supprimer_devraitRetourner400SiInterdit() throws Exception {
            doThrow(new BusinessException("Suppression interdite"))
                    .when(examenService).supprimer(1L);

            mockMvc.perform(delete("/api/v1/examens/1"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Doit retourner 404 si examen introuvable")
        void supprimer_devraitRetourner404SiIntrouvable() throws Exception {
            doThrow(new ResourceNotFoundException("Examen", 99L))
                    .when(examenService).supprimer(99L);

            mockMvc.perform(delete("/api/v1/examens/99"))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // POST /api/v1/examens/{id}/pdf
    // ================================================================

    @Nested
    @DisplayName("POST /api/v1/examens/{id}/pdf")
    class ImporterPdf {

        @Test
        @DisplayName("Doit retourner 200 après import PDF")
        void importerPdf_devraitRetourner200() throws Exception {
            examenResponse.setHasPdfSujet(true);
            examenResponse.setPdfSujetNom("sujet.pdf");
            when(examenService.importerPdf(eq(1L), any())).thenReturn(examenResponse);

            MockMultipartFile fichier = new MockMultipartFile(
                    "fichier", "sujet.pdf", "application/pdf",
                    "contenu pdf".getBytes());

            mockMvc.perform(multipart("/api/v1/examens/1/pdf").file(fichier))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.hasPdfSujet").value(true));
        }

        @Test
        @DisplayName("Doit retourner 400 si fichier non PDF")
        void importerPdf_devraitRetourner400SiNonPdf() throws Exception {
            when(examenService.importerPdf(eq(1L), any()))
                    .thenThrow(new BusinessException("Seuls les fichiers PDF sont acceptés"));

            MockMultipartFile fichier = new MockMultipartFile(
                    "fichier", "doc.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "contenu".getBytes());

            mockMvc.perform(multipart("/api/v1/examens/1/pdf").file(fichier))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }
}
