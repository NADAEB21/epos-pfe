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
import tn.epos.scoring_service.dto.EtudiantDTO;
import tn.epos.scoring_service.entities.Etudiant;
import tn.epos.scoring_service.service.EtudiantService;
import tn.epos.common.exception.ConflictException;
import static org.hamcrest.Matchers.containsString;

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
    private static final String TEST_EMAIL = "mohamed.benali@example.com";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        etudiant = new Etudiant();
        etudiant.setId(1L);
        etudiant.setNom("Ben Ali");
        etudiant.setPrenom("Mohamed");
        etudiant.setNumero_inscription("2024-001");
        etudiant.setEmail(TEST_EMAIL);
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
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].nom").value("Ben Ali"))
                    .andExpect(jsonPath("$.data[0].prenom").value("Mohamed"))
                    .andExpect(jsonPath("$.data[0].email").value(TEST_EMAIL));

            verify(etudiantService, times(1)).getAllEtudiants();
        }

        @Test
        @DisplayName("200 - Retourne une liste vide si aucun étudiant")
        void getAll_devraitRetourner200AvecListeVide() throws Exception {
            when(etudiantService.getAllEtudiants()).thenReturn(List.of());

            mockMvc.perform(get("/api/etudiants"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
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
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.nom").value("Ben Ali"))
                    .andExpect(jsonPath("$.data.numero_inscription").value("2024-001"))
                    .andExpect(jsonPath("$.data.email").value(TEST_EMAIL));
        }

        @Test
        @DisplayName("404 - Étudiant introuvable")
        void getById_devraitRetourner404() throws Exception {
            when(etudiantService.getEtudiantById(99L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/etudiants/99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // ─── POST /api/etudiants ─────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/etudiants")
    class Create {

        @Test
        @DisplayName("201 - Étudiant créé avec succès")
        void create_devraitRetourner201() throws Exception {
            when(etudiantService.saveEtudiant(any(Etudiant.class))).thenReturn(etudiant);

            EtudiantDTO requestDto = new EtudiantDTO(null, "Ben Ali", "Mohamed", "2024-001", TEST_EMAIL);

            mockMvc.perform(post("/api/etudiants")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(requestDto)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.nom").value("Ben Ali"))
                    .andExpect(jsonPath("$.data.prenom").value("Mohamed"))
                    .andExpect(jsonPath("$.data.numero_inscription").value("2024-001"))
                    .andExpect(jsonPath("$.data.email").value(TEST_EMAIL));

            verify(etudiantService, times(1)).saveEtudiant(any(Etudiant.class));
        }

        @Test
        @DisplayName("409 - Numéro d'inscription déjà utilisé par un autre étudiant")
        void create_numeroDejaUtilise_devraitRetourner409() throws Exception {
            when(etudiantService.saveEtudiant(any(Etudiant.class)))
                    .thenThrow(new ConflictException(
                            "Le numéro d'inscription « 481 » est déjà utilisé par Yassine Khelifi (id 2)."));

            EtudiantDTO requestDto = new EtudiantDTO(null, "Khelifi", "Yassine2", "481", null);

            mockMvc.perform(post("/api/etudiants")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(requestDto)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(containsString("Khelifi")));
        }
    }

    // ─── PUT /api/etudiants/{id} ─────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/etudiants/{id}")
    class Update {

        /**
         * #215/#227 — un appelant étroit (la saisie rapide d'e-mail sur les
         * convocations, l'édition en ligne du roster) n'envoie QUE l'adresse.
         * L'ancienne version recopiait tous les champs du DTO et effaçait donc
         * le nom, le prénom et le numéro d'inscription au passage.
         */
        @Test
        @DisplayName("PUT partiel : n'écrase PAS les champs absents du corps")
        void update_partiel_neDoitPasEffacerLesAutresChamps() throws Exception {
            when(etudiantService.getEtudiantById(1L)).thenReturn(Optional.of(etudiant));
            when(etudiantService.saveEtudiant(any(Etudiant.class))).thenAnswer(inv -> inv.getArgument(0));

            mockMvc.perform(put("/api/etudiants/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"nouvelle@etu.tn\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.email").value("nouvelle@etu.tn"))
                    // Intacts — c'est tout l'objet du test.
                    .andExpect(jsonPath("$.data.nom").value("Ben Ali"))
                    .andExpect(jsonPath("$.data.prenom").value("Mohamed"))
                    .andExpect(jsonPath("$.data.numero_inscription").value("2024-001"));
        }

        @Test
        @DisplayName("PUT avec e-mail vide : effacement EXPLICITE, autorisé")
        void update_emailVide_devraitEffacer() throws Exception {
            when(etudiantService.getEtudiantById(1L)).thenReturn(Optional.of(etudiant));
            when(etudiantService.saveEtudiant(any(Etudiant.class))).thenAnswer(inv -> inv.getArgument(0));

            // null = "je n'y touche pas" ; "" = "retire-la". Une adresse fausse
            // est pire que pas d'adresse : il faut pouvoir la retirer.
            mockMvc.perform(put("/api/etudiants/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.email").value(""))
                    .andExpect(jsonPath("$.data.nom").value("Ben Ali"));
        }

        @Test
        @DisplayName("200 - Étudiant mis à jour")
        void update_devraitRetourner200() throws Exception {
            String newEmail = "ali.bensalah@example.com";
            Etudiant updated = new Etudiant();
            updated.setId(1L);
            updated.setNom("Ben Salah");
            updated.setPrenom("Ali");
            updated.setNumero_inscription("2024-002");
            updated.setEmail(newEmail);

            when(etudiantService.getEtudiantById(1L)).thenReturn(Optional.of(etudiant));
            when(etudiantService.saveEtudiant(any(Etudiant.class))).thenReturn(updated);

            EtudiantDTO updateDto = new EtudiantDTO(1L, "Ben Salah", "Ali", "2024-002", newEmail);

            mockMvc.perform(put("/api/etudiants/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.nom").value("Ben Salah"))
                    .andExpect(jsonPath("$.data.prenom").value("Ali"))
                    .andExpect(jsonPath("$.data.email").value(newEmail));
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

        @Test
        @DisplayName("409 - Impossible de renommer vers un numéro déjà pris par un autre étudiant")
        void update_renommageVersNumeroExistant_devraitRetourner409() throws Exception {
            when(etudiantService.getEtudiantById(1L)).thenReturn(Optional.of(etudiant));
            when(etudiantService.saveEtudiant(any(Etudiant.class)))
                    .thenThrow(new ConflictException(
                            "Le numéro d'inscription « 481 » est déjà utilisé par Yassine Khelifi (id 2)."));

            mockMvc.perform(put("/api/etudiants/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"numero_inscription\":\"481\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(containsString("Khelifi")));
        }
    }

    // ─── DELETE /api/etudiants/{id} ──────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/etudiants/{id}")
    class Delete {

        @Test
        @DisplayName("200 - Étudiant supprimé")
        void delete_devraitRetourner200() throws Exception {
            when(etudiantService.getEtudiantById(1L)).thenReturn(Optional.of(etudiant));
            doNothing().when(etudiantService).deleteEtudiant(1L);

            mockMvc.perform(delete("/api/etudiants/1"))
                    .andExpect(status().isOk());

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