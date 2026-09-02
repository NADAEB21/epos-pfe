package tn.epos.auth_service.service.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Pure unit tests for {@link SpringMailEmailService}. JavaMailSender is mocked,
 * so no SMTP is required. Verifies the composed message has the right
 * recipient, sender, subject and a body that embeds the reset link.
 */
@ExtendWith(MockitoExtension.class)
class SpringMailEmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Test
    void sendPasswordResetEmail_buildsMessageWithEncodedTokenInResetUrl() {
        SpringMailEmailService svc = new SpringMailEmailService(
                mailSender,
                "noreply@epos.tn",
                "http://localhost:4200/reset-password");

        svc.sendPasswordResetEmail("eval@epos.tn", "raw-token-xyz");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        SimpleMailMessage msg = captor.getValue();
        assertThat(msg.getFrom()).isEqualTo("noreply@epos.tn");
        assertThat(msg.getTo()).containsExactly("eval@epos.tn");
        assertThat(msg.getSubject()).contains("EPOS");
        assertThat(msg.getText())
                .contains("http://localhost:4200/reset-password?token=raw-token-xyz")
                .contains("30 minutes");
    }

    @Test
    void sendInvitationEmail_linkCarriesBienvenueFlag_codeAndSevenDays() {
        // #389 — le lien ouvre la page « Bienvenue » (bienvenue=1), le code brut
        // reste copiable (mobile), la validite annoncee est celle du jeton (7 jours),
        // et la personne est nommee.
        SpringMailEmailService svc = new SpringMailEmailService(
                mailSender, "eposfphm@gmail.com", "http://localhost:4200/reset-password");

        svc.sendInvitationEmail("rania@epos.tn", "raw-invite", "Rania", "Aouina");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage msg = captor.getValue();
        assertThat(msg.getFrom()).isEqualTo("eposfphm@gmail.com");
        assertThat(msg.getTo()).containsExactly("rania@epos.tn");
        assertThat(msg.getSubject()).contains("compte est créé");
        assertThat(msg.getText())
                .contains("Bonjour Rania Aouina")
                .contains("http://localhost:4200/reset-password?token=raw-invite&bienvenue=1")
                .contains("    raw-invite")
                .contains("7 jours")
                .doesNotContain("30 minutes");
        assertThat(svc.estSimule()).isFalse();
    }

    @Test
    void sendPasswordResetEmail_urlEncodesTokenWithSpecialCharacters() {
        SpringMailEmailService svc = new SpringMailEmailService(
                mailSender,
                "noreply@epos.tn",
                "http://localhost:4200/reset-password");

        // Refresh tokens are URL-safe base64 in practice but verify defensive encoding
        svc.sendPasswordResetEmail("eval@epos.tn", "token with space&special=chars");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        // URL-encoded form of the token must appear in the link
        assertThat(captor.getValue().getText())
                .contains("token+with+space%26special%3Dchars");
    }
}
