package tn.epos.scoring_service.service.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.epos.scoring_service.dto.ConvocationDTO;

import java.time.LocalDate;
import java.time.Month;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #227 — l'implémentation par défaut. Elle protège les vrais étudiants : tant
 * que la messagerie n'est pas explicitement activée, rien ne part.
 */
@DisplayName("StubConvocationEmailService - envoi simulé (#227)")
class StubConvocationEmailServiceTest {

    private ConvocationDTO convocation(String email) {
        return new ConvocationDTO(1L, 10L, "Werghi", "Ines", "D227-07", email,
                7, 2L, 2, LocalDate.of(2026, Month.JULY, 28), "09:20", null);
    }

    @Test
    @DisplayName("estSimule() est true — l'écran doit pouvoir le dire à l'utilisateur")
    void estSimule_devraitEtreTrue() {
        assertThat(new StubConvocationEmailService().estSimule()).isTrue();
    }

    @Test
    @DisplayName("L'envoi est capturé en mémoire, avec de quoi vérifier son contenu")
    void envoi_devraitEtreCapture() {
        StubConvocationEmailService service = new StubConvocationEmailService();

        service.envoyerConvocation(convocation("ines@etu.tn"), "EPOS Biologie");

        assertThat(service.captured()).hasSize(1);
        StubConvocationEmailService.ConvocationEnvoyee c = service.captured().get(0);
        assertThat(c.email()).isEqualTo("ines@etu.tn");
        assertThat(c.examenNom()).isEqualTo("EPOS Biologie");
        assertThat(c.lotNumero()).isEqualTo(2);
        assertThat(c.jour()).isEqualTo("2026-07-28");
        assertThat(c.heure()).isEqualTo("09:20");
    }

    @Test
    @DisplayName("captured() est une vue immuable : un test ne peut pas la corrompre")
    void captured_devraitEtreImmuable() {
        StubConvocationEmailService service = new StubConvocationEmailService();
        service.envoyerConvocation(convocation("a@etu.tn"), "X");

        assertThat(service.captured()).isUnmodifiable();
    }

    @Test
    @DisplayName("clear() repart de zéro entre deux scénarios")
    void clear_devraitViderLesCaptures() {
        StubConvocationEmailService service = new StubConvocationEmailService();
        service.envoyerConvocation(convocation("a@etu.tn"), "X");
        service.envoyerConvocation(convocation("b@etu.tn"), "X");
        assertThat(service.captured()).hasSize(2);

        service.clear();

        assertThat(service.captured()).isEmpty();
    }
}
