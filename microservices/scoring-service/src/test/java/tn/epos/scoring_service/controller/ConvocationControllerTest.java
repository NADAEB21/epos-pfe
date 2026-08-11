package tn.epos.scoring_service.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tn.epos.scoring_service.config.TestSecurityConfig;
import tn.epos.scoring_service.dto.ConvocationDTO;
import tn.epos.scoring_service.dto.EnvoiConvocationsResult;
import tn.epos.scoring_service.service.ConvocationService;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ConvocationController.class)
@TestPropertySource(locations = "classpath:application-test.properties")
@Import(TestSecurityConfig.class)
@DisplayName("ConvocationController - convocations (#227)")
class ConvocationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConvocationService convocationService;

    private ConvocationDTO convocation() {
        return new ConvocationDTO(1L, 10L, "Werghi", "Ines", "D227-07", "ines.werghi@etu.tn",
                7, 2L, 2, LocalDate.of(2026, Month.JULY, 28), "09:20", null);
    }

    @Test
    @DisplayName("GET — renvoie les convocations dérivées côté serveur")
    void lister_devraitRetourner200() throws Exception {
        when(convocationService.construire(51L)).thenReturn(List.of(convocation()));

        mockMvc.perform(get("/api/convocations/examens/51"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].nom").value("Werghi"))
                .andExpect(jsonPath("$.data[0].lotNumero").value(2))
                // L'heure est CALCULÉE PAR LE BACKEND : c'est ce que le web lit au
                // lieu de la recalculer, et ce que l'e-mail reprend (#227).
                .andExpect(jsonPath("$.data[0].heureConvocation").value("09:20"))
                .andExpect(jsonPath("$.data[0].jour").value("2026-07-28"))
                .andExpect(jsonPath("$.data[0].ordre_import").value(7));

        verify(convocationService, times(1)).construire(51L);
    }

    @Test
    @DisplayName("GET — un examen sans lots répartis renvoie une liste vide, pas une erreur")
    void lister_sansLots_devraitRetournerListeVide() throws Exception {
        when(convocationService.construire(51L)).thenReturn(List.of());

        mockMvc.perform(get("/api/convocations/examens/51"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("POST — le bilan par étudiant est renvoyé, sans-adresse compté à part")
    void envoyer_devraitRetournerLeBilan() throws Exception {
        EnvoiConvocationsResult result = new EnvoiConvocationsResult(3, 2, 1, 0, false, List.of(
                new EnvoiConvocationsResult.EnvoiLigne(1L, "Werghi", "Ines", "i@etu.tn", "ENVOYE", null),
                new EnvoiConvocationsResult.EnvoiLigne(2L, "Amri", "Sarra", null, "SANS_ADRESSE",
                        "Pas d'adresse e-mail — convocation à remettre en main propre.")));
        when(convocationService.envoyer(51L)).thenReturn(result);

        mockMvc.perform(post("/api/convocations/examens/51/envoyer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("2 convocation(s) envoyée(s)."))
                .andExpect(jsonPath("$.data.envoyes").value(2))
                .andExpect(jsonPath("$.data.sansAdresse").value(1))
                .andExpect(jsonPath("$.data.simule").value(false))
                .andExpect(jsonPath("$.data.lignes[1].statut").value("SANS_ADRESSE"));
    }

    /**
     * L'état PAR DÉFAUT du serveur. Le message doit dire que rien n'est parti :
     * annoncer « envoyé » alors que la messagerie est coupée ferait croire au
     * responsable que sa promotion est convoquée.
     */
    @Test
    @DisplayName("POST — mode simulé : le message l'annonce, il ne prétend pas avoir envoyé")
    void envoyer_simule_devraitLeDire() throws Exception {
        when(convocationService.envoyer(51L))
                .thenReturn(new EnvoiConvocationsResult(1, 1, 0, 0, true, List.of()));

        mockMvc.perform(post("/api/convocations/examens/51/envoyer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("Envoi simulé (messagerie désactivée) — aucun e-mail n'est parti."))
                .andExpect(jsonPath("$.data.simule").value(true));
    }

    @Test
    @DisplayName("POST — des échecs d'envoi restent un 200 avec le détail, pas un 500 global")
    void envoyer_avecEchecs_devraitResterUn200Detaille() throws Exception {
        // Un 500 ferait disparaître l'information « ceux-là sont bien partis ».
        when(convocationService.envoyer(51L)).thenReturn(new EnvoiConvocationsResult(
                2, 1, 0, 1, false, List.of(
                        new EnvoiConvocationsResult.EnvoiLigne(1L, "A", "A", "a@etu.tn", "ENVOYE", null),
                        new EnvoiConvocationsResult.EnvoiLigne(2L, "B", "B", "b@etu.tn", "ECHEC",
                                "Échec de l'envoi : boîte pleine"))));

        mockMvc.perform(post("/api/convocations/examens/51/envoyer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.echecs").value(1))
                .andExpect(jsonPath("$.data.envoyes").value(1))
                .andExpect(jsonPath("$.data.lignes[1].message").value("Échec de l'envoi : boîte pleine"));
    }
}
