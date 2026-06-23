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
import tn.epos.scoring_service.entities.Notation;
import tn.epos.scoring_service.entities.NotationItem;
import tn.epos.scoring_service.service.NotationItemService;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = NotationItemController.class)
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(TestSecurityConfig.class)
@DisplayName("NotationItemController - Tests unitaires")
class NotationItemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotationItemService service;

    private ObjectMapper objectMapper;
    private NotationItem item;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        Notation notation = new Notation();
        notation.setId(5L);
        notation.setScore_final(17.5f);
        notation.setVerouillee(false);

        item = new NotationItem();
        item.setId(1L);
        item.setItemId(100L);
        item.setValeur(15.0f);
        item.setCommentaire("Bonne réponse");
        item.setNotation(notation);
    }

    // ─── GET /api/notation-items ──────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/notation-items")
    class GetAll {

        @Test
        @DisplayName("200 - Retourne tous les items")
        void getAll_devraitRetourner200AvecListe() throws Exception {
            when(service.findAll()).thenReturn(List.of(item));

            mockMvc.perform(get("/api/notation-items"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].valeur").value(15.0));

            verify(service, times(1)).findAll();
        }

        @Test
        @DisplayName("200 - Retourne une liste vide")
        void getAll_devraitRetourner200AvecListeVide() throws Exception {
            when(service.findAll()).thenReturn(List.of());

            mockMvc.perform(get("/api/notation-items"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    // ─── GET /api/notation-items/notation/{notationId} ────────────────────────

    @Nested
    @DisplayName("GET /api/notation-items/notation/{notationId}")
    class GetByNotation {

        @Test
        @DisplayName("200 - Retourne les items d'une notation")
        void getByNotation_devraitRetourner200() throws Exception {
            when(service.findByNotation(5L)).thenReturn(List.of(item));

            mockMvc.perform(get("/api/notation-items/notation/5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].item_id").value(100))
                    .andExpect(jsonPath("$.data[0].valeur").value(15.0));
        }
    }

    // ─── GET /api/notation-items/{id} ─────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/notation-items/{id}")
    class GetById {

        @Test
        @DisplayName("200 - Item trouvé")
        void getById_devraitRetourner200() throws Exception {
            when(service.findById(1L)).thenReturn(Optional.of(item));

            mockMvc.perform(get("/api/notation-items/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.commentaire").value("Bonne réponse"));
        }
    }

    // ─── POST /api/notation-items ─────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/notation-items")
    class Create {

        @Test
        @DisplayName("201 - Item créé avec succès")
        void create_devraitRetourner200() throws Exception {
            when(service.save(any(NotationItem.class))).thenReturn(item);

            mockMvc.perform(post("/api/notation-items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(item)))
                    .andExpect(status().isCreated()) // Matches controller 201
                    .andExpect(jsonPath("$.data.valeur").value(15.0));
        }
    }

    // ─── PUT /api/notation-items/{id} ─────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/notation-items/{id}")
    class Update {

        @Test
        @DisplayName("200 - Item mis à jour")
        void update_devraitRetourner200() throws Exception {
            NotationItem updated = new NotationItem();
            updated.setId(1L);
            updated.setItemId(200L);
            updated.setValeur(18.0f);

            when(service.update(eq(1L), any(NotationItem.class))).thenReturn(updated);

            mockMvc.perform(put("/api/notation-items/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updated)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.valeur").value(18.0));
        }
    }

    // ─── DELETE /api/notation-items/{id} ──────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/notation-items/{id}")
    class Delete {

        @Test
        @DisplayName("200 - Item supprimé") // Changed from 204 to 200
        void delete_devraitRetourner204() throws Exception {
            doNothing().when(service).delete(1L);

            mockMvc.perform(delete("/api/notation-items/1"))
                    .andExpect(status().isOk()) // Returns ApiResponse now
                    .andExpect(jsonPath("$.success").value(true));

            verify(service, times(1)).delete(1L);
        }
    }
}