package tn.epos.auth_service.service.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Production implementation backed by Spring's JavaMailSender.
 * Active only when {@code app.mail.enabled=true} — SMTP host/port are then
 * configured via the standard {@code spring.mail.*} properties.
 *
 * Constructor injection (rather than @Value field injection) keeps the class
 * trivially testable with a mocked JavaMailSender, no Spring context needed.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
public class SpringMailEmailService implements EmailService {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String resetBaseUrl;

    public SpringMailEmailService(
            JavaMailSender mailSender,
            @Value("${app.mail.from:noreply@epos.tn}") String fromAddress,
            @Value("${app.mail.reset-base-url}") String resetBaseUrl) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.resetBaseUrl = resetBaseUrl;
    }

    @Override
    public void sendPasswordResetEmail(String recipientEmail, String rawResetToken) {
        String resetUrl = resetBaseUrl + "?token="
                + URLEncoder.encode(rawResetToken, StandardCharsets.UTF_8);

        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(fromAddress);
        msg.setTo(recipientEmail);
        msg.setSubject("EPOS — Réinitialisation du mot de passe");
        msg.setText("""
                Bonjour,

                Pour réinitialiser votre mot de passe, cliquez sur le lien ci-dessous :
                %s

                Ce lien expire dans 30 minutes. Si vous n'êtes pas à l'origine de cette demande,
                ignorez cet e-mail.

                — EPOS
                """.formatted(resetUrl));

        mailSender.send(msg);
        log.info("Password-reset email dispatched to {}", recipientEmail);
    }
}
