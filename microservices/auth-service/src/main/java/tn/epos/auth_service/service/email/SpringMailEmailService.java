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
 *
 * Le corps du mail affiche le token BRUT comme un « code » à copier-coller
 * (en plus du lien classique). L'app Flutter n'ayant pas encore de
 * deep-linking (voir CLAUDE.md / mémoire du projet), l'évaluateur doit
 * pouvoir coller ce code directement dans l'écran "Mot de passe oublié" —
 * un lien seul l'obligerait à aller l'extraire manuellement de l'URL.
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

                Vous avez demandé la réinitialisation de votre mot de passe EPOS.

                Sur l'application mobile, saisissez ce code dans l'écran
                "Mot de passe oublié" :

                    %s

                Ce code est valable 30 minutes et à usage unique.

                Depuis un ordinateur, vous pouvez aussi utiliser ce lien :
                %s

                Si vous n'êtes pas à l'origine de cette demande, ignorez cet e-mail :
                votre mot de passe actuel reste inchangé.

                — EPOS
                """.formatted(rawResetToken, resetUrl));

        mailSender.send(msg);
        log.info("Password-reset email dispatched to {}", recipientEmail);
    }
}