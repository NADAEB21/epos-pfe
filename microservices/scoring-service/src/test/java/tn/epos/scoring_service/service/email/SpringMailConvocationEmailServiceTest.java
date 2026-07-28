package tn.epos.scoring_service.service.email;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import tn.epos.scoring_service.dto.ConvocationDTO;

import java.time.LocalDate;
import java.time.Month;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * #227 — le CONTENU de la convocation reçue par l'étudiant.
 *
 * <p>C'est le seul endroit où ce texte est vérifié, et il part à de vraies
 * personnes : ces tests pinnent ce que la convocation promet (lot, jour, heure)
 * et surtout ce qu'elle NE promet PAS — ni salle (ADR-0014-A §4, le champ n'existe
 * pas), ni ordre de passage par station (tiré sur place, après l'appel).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SpringMailConvocationEmailService - contenu de la convocation (#227)")
class SpringMailConvocationEmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    private SpringMailConvocationEmailService service;

    @BeforeEach
    void setUp() {
        service = new SpringMailConvocationEmailService(mailSender, "noreply@epos.tn");
    }

    private ConvocationDTO convocation() {
        return new ConvocationDTO(
                1L, 10L, "Werghi", "Ines", "D227-07", "ines.werghi@etu.tn",
                7, 2L, 2, LocalDate.of(2026, Month.JULY, 28), "09:20", null);
    }

    private SimpleMailMessage envoyerEtCapturer(ConvocationDTO c) {
        service.envoyerConvocation(c, "EPOS Biologie");
        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("Adresse, expéditeur et objet")
    void enveloppe_devraitEtreCorrecte() {
        SimpleMailMessage msg = envoyerEtCapturer(convocation());

        assertThat(Objects.requireNonNull(msg.getTo())).containsExactly("ines.werghi@etu.tn");
        assertThat(msg.getFrom()).isEqualTo("noreply@epos.tn");
        assertThat(msg.getSubject()).isEqualTo("Convocation — EPOS Biologie");
    }

    @Test
    @DisplayName("Le corps porte le lot, le jour et l'heure — de quoi se présenter")
    void corps_devraitPorterLotJourHeure() {
        String corps = Objects.requireNonNull(envoyerEtCapturer(convocation()).getText());

        assertThat(corps)
                .contains("Ines Werghi")
                .contains("EPOS Biologie")
                .contains("28/07/2026")   // le jour de SON lot, pas celui de l'examen
                .contains("09:20")
                .contains("D227-07");
        assertThat(corps).containsPattern("Groupe \\(lot\\)\\s*:\\s*2");
    }

    @Test
    @DisplayName("Le corps ne promet NI salle NI ordre de passage")
    void corps_neDoitRienPromettreQuOnIgnore() {
        String corps = Objects.requireNonNull(envoyerEtCapturer(convocation()).getText());

        // ADR-0014-A §4 : le champ « lieu » a été examiné puis écarté, la
        // convocation ne le porte pas. Le promettre serait inventer une donnée.
        assertThat(corps).doesNotContainIgnoringCase("salle");
        // L'ordre entre stations dépend de la présence, constatée le jour même.
        assertThat(corps).doesNotContainPattern("(?i)station \\d");
        assertThat(corps).contains("communiqué sur place");
    }

    @Test
    @DisplayName("Champs manquants : des tirets, jamais « null » dans un e-mail d'étudiant")
    void champsManquants_neDoiventPasAfficherNull() {
        ConvocationDTO creuse = new ConvocationDTO(
                1L, 10L, null, null, null, "x@etu.tn", null, 2L, null, null, null, null);

        String corps = Objects.requireNonNull(envoyerEtCapturer(creuse).getText());

        assertThat(corps).doesNotContain("null");
    }

    @Test
    @DisplayName("estSimule() est false : c'est l'implémentation qui envoie vraiment")
    void estSimule_devraitEtreFalse() {
        assertThat(service.estSimule()).isFalse();
    }

    @Test
    @DisplayName("Un échec SMTP remonte — l'appelant en fait une ligne ECHEC")
    void echecSmtp_devraitRemonter() {
        doThrow(new MailSendException("boîte pleine")).when(mailSender).send((SimpleMailMessage) any());

        assertThatThrownBy(() -> service.envoyerConvocation(convocation(), "EPOS Biologie"))
                .isInstanceOf(MailSendException.class);
    }

    private static SimpleMailMessage any() {
        return org.mockito.ArgumentMatchers.any(SimpleMailMessage.class);
    }
}
