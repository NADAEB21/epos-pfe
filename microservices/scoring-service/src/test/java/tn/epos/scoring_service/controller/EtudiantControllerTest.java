package tn.epos.scoring_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.scoring_service.config.TestSecurityConfig;
import tn.epos.scoring_service.entities.Etudiant;
import tn.epos.scoring_service.service.EtudiantService;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EtudiantController.class)
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(TestSecurityConfig.class)
@DisplayName("EtudiantController - Tests unitaires")
class EtudiantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EtudiantService etudiantService;

    private ObjectMapper objectMapper;
    private Etudiant etudiant;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        etudiant = new Etudiant();
        etudiant.setId(1L);
        etudiant.setNom("Ben Ali");
        etudiant.setPrenom("Mohamed");
        etudiant.setNumero_inscription("2024-001");
    }

    // ─── GET /api/etudiants ───────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/etudiants")
    class GetAll {

        @Test
        @DisplayName("200 - Retourne la liste de tous les étudiants")
        void getAll_devraitRetourner200AvecListe() throws Exception {
            when(etudiantService.getAllEtudiants()).thenReturn(List.of(etudiant));

            mockMvc.perform(get("/api/etudiants"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(1))
                    .andExpect(jsonPath("$[0].nom").value("Ben Ali"))
                    .andExpect(jsonPath("$[0].prenom").value("Mohamed"));

            verify(etudiantService, times(1)).getAllEtudiants();
        }

        @Test
        @DisplayName("200 - Retourne une liste vide si aucun étudiant")
        void getAll_devraitRetourner200AvecListeVide() throws Exception {
            when(etudiantService.getAllEtudiants()).thenReturn(List.of());

            mockMvc.perform(get("/api/etudiants"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$").isEmpty());
        }
    }

    // ─── GET /api/etudiants/{id} ─────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/etudiants/{id}")
    class GetById {

        @Test
        @DisplayName("200 - Étudiant trouvé")
        void getById_devraitRetourner200() throws Exception {
            when(etudiantService.getEtudiantById(1L)).thenReturn(Optional.of(etudiant));

            mockMvc.perform(get("/api/etudiants/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.nom").value("Ben Ali"))
                    .andExpect(jsonPath("$.numero_inscription").value("2024-001"));
        }

        @Test
        @DisplayName("404 - Étudiant introuvable")
        void getById_devraitRetourner404() throws Exception {
            when(etudiantService.getEtudiantById(99L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/etudiants/99"))
                    .andExpect(status().isNotFound());
        }
    }

    // ─── POST /api/etudiants ─────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/etudiants")
    class Create {

        @Test
        @DisplayName("200 - Étudiant créé avec succès")
        void create_devraitRetourner200() throws Exception {
            when(etudiantService.saveEtudiant(any(Etudiant.class))).thenReturn(etudiant);

            mockMvc.perform(post("/api/etudiants")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(etudiant)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nom").value("Ben Ali"))
                    .andExpect(jsonPath("$.prenom").value("Mohamed"));

            verify(etudiantService, times(1)).saveEtudiant(any(Etudiant.class));
        }
    }

    // ─── PUT /api/etudiants/{id} ─────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/etudiants/{id}")
    class Update {

        @Test
        @DisplayName("200 - Étudiant mis à jour")
        void update_devraitRetourner200() throws Exception {
            Etudiant updated = new Etudiant();
            updated.setId(1L);
            updated.setNom("Ben Salah");
            updated.setPrenom("Ali");
            updated.setNumero_inscription("2024-002");

            when(etudiantService.getEtudiantById(1L)).thenReturn(Optional.of(etudiant));
            when(etudiantService.saveEtudiant(any(Etudiant.class))).thenReturn(updated);

            mockMvc.perform(put("/api/etudiants/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updated)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nom").value("Ben Salah"))
                    .andExpect(jsonPath("$.prenom").value("Ali"));
        }

        @Test
        @DisplayName("404 - Étudiant introuvable à la mise à jour")
        void update_devraitRetourner404SiIntrouvable() throws Exception {
            when(etudiantService.getEtudiantById(99L)).thenReturn(Optional.empty());

            mockMvc.perform(put("/api/etudiants/99")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(etudiant)))
                    .andExpect(status().isNotFound());
        }
    }

    // ─── DELETE /api/etudiants/{id} ──────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/etudiants/{id}")
    class Delete {

        @Test
        @DisplayName("204 - Étudiant supprimé")
        void delete_devraitRetourner204() throws Exception {
            when(etudiantService.getEtudiantById(1L)).thenReturn(Optional.of(etudiant));
            doNothing().when(etudiantService).deleteEtudiant(1L);

            mockMvc.perform(delete("/api/etudiants/1"))
                    .andExpect(status().isNoContent());

            verify(etudiantService, times(1)).deleteEtudiant(1L);
        }

        @Test
        @DisplayName("404 - Étudiant introuvable à la suppression")
        void delete_devraitRetourner404SiIntrouvable() throws Exception {
            when(etudiantService.getEtudiantById(99L)).thenReturn(Optional.empty());

            mockMvc.perform(delete("/api/etudiants/99"))
                    .andExpect(status().isNotFound());

            verify(etudiantService, never()).deleteEtudiant(anyLong());
        }
    }
}
