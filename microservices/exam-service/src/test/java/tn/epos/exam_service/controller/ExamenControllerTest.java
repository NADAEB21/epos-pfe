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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.exam_service.controllers.ExamenController;
import tn.epos.exam_service.dto.request.ExamenRequest;
import tn.epos.exam_service.dto.response.ExamenResponse;
import tn.epos.exam_service.enums.StatutExamen;
import tn.epos.exam_service.exception.BusinessException;
import tn.epos.exam_service.exception.GlobalExceptionHandler;
import tn.epos.exam_service.exception.ResourceNotFoundException;
import tn.epos.exam_service.services.ExamenService;
import org.springframework.context.annotation.Import;
import tn.epos.exam_service.config.TestSecurityConfig;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {ExamenController.class, GlobalExceptionHandler.class})
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(TestSecurityConfig.class)
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
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        examenResponse = new ExamenResponse();
        examenResponse.setId(1L);
        examenResponse.setNom("Examen Test");
        examenResponse.setMatiere("Chimie");
        examenResponse.setDateExamen(LocalDate.of(2024, 6, 15));
        examenResponse.setStatut(StatutExamen.BROUILLON);
        examenResponse.setDureeStationMin(15);
        examenResponse.setNbEtudiantsParStation(4);
        examenResponse.setHasPdfSujet(false);

        examenRequest = new ExamenRequest();
        examenRequest.setNom("Examen Test");
        examenRequest.setMatiere("Chimie");
        examenRequest.setDateExamen(LocalDate.of(2024, 6, 15));
        examenRequest.setDureeStationMin(15);
        examenRequest.setNbEtudiantsParStation(4);
    }

    // ================================================================
    // POST /api/examens
    // ================================================================

    @Nested
    @DisplayName("POST /api/examens")
    class Creer {

        @Test
        @DisplayName("201 - Examen créé avec succès")
        void creer_devraitRetourner201() throws Exception {
            when(examenService.creer(any(ExamenRequest.class))).thenReturn(examenResponse);

            mockMvc.perform(post("/api/examens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(examenRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.nom").value("Examen Test"))
                    .andExpect(jsonPath("$.data.statut").value("BROUILLON"));

            verify(examenService, times(1)).creer(any());
        }

        @Test
        @DisplayName("400 - Nom vide")
        void creer_nomVide_devraitRetourner400() throws Exception {
            examenRequest.setNom("");

            mockMvc.perform(post("/api/examens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(examenRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 - Matière vide")
        void creer_matiereVide_devraitRetourner400() throws Exception {
            examenRequest.setMatiere("");

            mockMvc.perform(post("/api/examens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(examenRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 - Date manquante")
        void creer_dateManquante_devraitRetourner400() throws Exception {
            examenRequest.setDateExamen(null);

            mockMvc.perform(post("/api/examens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(examenRequest)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ================================================================
    // GET /api/examens
    // ================================================================

    @Nested
    @DisplayName("GET /api/examens")
    class Lister {

        @Test
        @DisplayName("200 - Liste tous les examens sans filtre")
        void lister_sansStatut_devraitAppelerListerTous() throws Exception {
            Page<ExamenResponse> page = new PageImpl<>(List.of(examenResponse));
            when(examenService.listerTous(any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/examens"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.content[0].nom").value("Examen Test"))
                    .andExpect(jsonPath("$.data.totalElements").value(1));

            verify(examenService).listerTous(any(Pageable.class));
            verify(examenService, never()).listerParStatut(any(), any());
        }

        @Test
        @DisplayName("200 - Filtrage par statut BROUILLON")
        void lister_avecStatut_devraitAppelerListerParStatut() throws Exception {
            Page<ExamenResponse> page = new PageImpl<>(List.of(examenResponse));
            when(examenService.listerParStatut(eq(StatutExamen.BROUILLON), any(Pageable.class)))
                    .thenReturn(page);

            mockMvc.perform(get("/api/examens").param("statut", "BROUILLON"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].statut").value("BROUILLON"));

            verify(examenService).listerParStatut(eq(StatutExamen.BROUILLON), any(Pageable.class));
            verify(examenService, never()).listerTous(any());
        }

        @Test
        @DisplayName("200 - Liste vide")
        void lister_listeVide_devraitRetourner200AvecListeVide() throws Exception {
            Page<ExamenResponse> page = new PageImpl<>(List.of());
            when(examenService.listerTous(any(Pageable.class))).thenReturn(page);

            mockMvc.perform(get("/api/examens"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isEmpty())
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }
    }

    // ================================================================
    // GET /api/examens/{id}
    // ================================================================

    @Nested
    @DisplayName("GET /api/examens/{id}")
    class TrouverParId {

        @Test
        @DisplayName("200 - Examen trouvé")
        void trouverParId_devraitRetourner200() throws Exception {
            when(examenService.trouverParId(1L)).thenReturn(examenResponse);

            mockMvc.perform(get("/api/examens/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.nom").value("Examen Test"));
        }

        @Test
        @DisplayName("404 - Examen introuvable")
        void trouverParId_introuvable_devraitRetourner404() throws Exception {
            when(examenService.trouverParId(99L))
                    .thenThrow(new ResourceNotFoundException("Examen", 99L));

            mockMvc.perform(get("/api/examens/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ================================================================
    // PUT /api/examens/{id}
    // ================================================================

    @Nested
    @DisplayName("PUT /api/examens/{id}")
    class Modifier {

        @Test
        @DisplayName("200 - Examen modifié")
        void modifier_devraitRetourner200() throws Exception {
            when(examenService.modifier(eq(1L), any(ExamenRequest.class)))
                    .thenReturn(examenResponse);

            mockMvc.perform(put("/api/examens/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(examenRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Examen modifié avec succès"));
        }

        @Test
        @DisplayName("400 - Statut non BROUILLON")
        void modifier_statutNonBrouillon_devraitRetourner400() throws Exception {
            when(examenService.modifier(eq(1L), any()))
                    .thenThrow(new BusinessException("Statut invalide"));

            mockMvc.perform(put("/api/examens/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(examenRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("404 - Examen introuvable")
        void modifier_introuvable_devraitRetourner404() throws Exception {
            when(examenService.modifier(eq(99L), any()))
                    .thenThrow(new ResourceNotFoundException("Examen", 99L));

            mockMvc.perform(put("/api/examens/99")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(examenRequest)))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // PATCH /api/examens/{id}/statut
    // ================================================================

    @Nested
    @DisplayName("PATCH /api/examens/{id}/statut")
    class ChangerStatut {

        @Test
        @DisplayName("200 - BROUILLON → CONFIGURE")
        void changerStatut_brouillonVersConfigure_devraitRetourner200() throws Exception {
            examenResponse.setStatut(StatutExamen.CONFIGURE);
            when(examenService.changerStatut(1L, StatutExamen.CONFIGURE))
                    .thenReturn(examenResponse);

            mockMvc.perform(patch("/api/examens/1/statut").param("statut", "CONFIGURE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.statut").value("CONFIGURE"));
        }

        @Test
        @DisplayName("400 - Transition invalide BROUILLON → EN_COURS")
        void changerStatut_transitionInvalide_devraitRetourner400() throws Exception {
            when(examenService.changerStatut(1L, StatutExamen.EN_COURS))
                    .thenThrow(new BusinessException("Transition invalide"));

            mockMvc.perform(patch("/api/examens/1/statut").param("statut", "EN_COURS"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("404 - Examen introuvable")
        void changerStatut_introuvable_devraitRetourner404() throws Exception {
            when(examenService.changerStatut(99L, StatutExamen.CONFIGURE))
                    .thenThrow(new ResourceNotFoundException("Examen", 99L));

            mockMvc.perform(patch("/api/examens/99/statut").param("statut", "CONFIGURE"))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // DELETE /api/examens/{id}
    // ================================================================

    @Nested
    @DisplayName("DELETE /api/examens/{id}")
    class Supprimer {

        @Test
        @DisplayName("200 - Examen supprimé")
        void supprimer_devraitRetourner200() throws Exception {
            doNothing().when(examenService).supprimer(1L);

            mockMvc.perform(delete("/api/examens/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("400 - Suppression interdite")
        void supprimer_interdit_devraitRetourner400() throws Exception {
            doThrow(new BusinessException("Suppression interdite"))
                    .when(examenService).supprimer(1L);

            mockMvc.perform(delete("/api/examens/1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("404 - Examen introuvable")
        void supprimer_introuvable_devraitRetourner404() throws Exception {
            doThrow(new ResourceNotFoundException("Examen", 99L))
                    .when(examenService).supprimer(99L);

            mockMvc.perform(delete("/api/examens/99"))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // POST /api/examens/{id}/pdf
    // ================================================================

    @Nested
    @DisplayName("POST /api/examens/{id}/pdf")
    class ImporterPdf {

        @Test
        @DisplayName("200 - PDF importé avec succès")
        void importerPdf_devraitRetourner200() throws Exception {
            examenResponse.setHasPdfSujet(true);
            examenResponse.setPdfSujetNom("sujet.pdf");
            when(examenService.importerPdf(eq(1L), any())).thenReturn(examenResponse);

            MockMultipartFile fichier = new MockMultipartFile(
                    "fichier", "sujet.pdf", "application/pdf",
                    "contenu pdf".getBytes());

            mockMvc.perform(multipart("/api/examens/1/pdf").file(fichier))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.hasPdfSujet").value(true))
                    .andExpect(jsonPath("$.data.pdfSujetNom").value("sujet.pdf"));
        }

        @Test
        @DisplayName("400 - Fichier non PDF")
        void importerPdf_nonPdf_devraitRetourner400() throws Exception {
            when(examenService.importerPdf(eq(1L), any()))
                    .thenThrow(new BusinessException("Seuls les fichiers PDF sont acceptés"));

            MockMultipartFile fichier = new MockMultipartFile(
                    "fichier", "doc.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "contenu".getBytes());

            mockMvc.perform(multipart("/api/examens/1/pdf").file(fichier))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("404 - Examen introuvable pour PDF")
        void importerPdf_examenIntrouvable_devraitRetourner404() throws Exception {
            when(examenService.importerPdf(eq(99L), any()))
                    .thenThrow(new ResourceNotFoundException("Examen", 99L));

            MockMultipartFile fichier = new MockMultipartFile(
                    "fichier", "sujet.pdf", "application/pdf", "contenu".getBytes());

            mockMvc.perform(multipart("/api/examens/99/pdf").file(fichier))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // GET /api/examens/{id}/pdf
    // ================================================================

    @Nested
    @DisplayName("GET /api/examens/{id}/pdf")
    class TelechargerPdf {

        @Test
        @DisplayName("404 - Aucun PDF importé")
        void telechargerPdf_aucunPdf_devraitRetourner404() throws Exception {
            when(examenService.obtenirCheminPdf(1L))
                    .thenThrow(new ResourceNotFoundException("Aucun PDF importé pour l'examen 1"));

            mockMvc.perform(get("/api/examens/1/pdf"))
                    .andExpect(status().isNotFound());
        }
    }
}
