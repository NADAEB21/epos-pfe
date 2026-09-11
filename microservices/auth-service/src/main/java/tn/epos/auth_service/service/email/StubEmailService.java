package tn.epos.auth_service.service.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test / development implementation. Captures every dispatched email in memory
 * so integration tests can assert on what was sent without standing up SMTP.
 * Logs that an email was dispatched, but never the raw token.
 *
 * <p>#389 — {@link #estSimule()} rend {@code true} : les écrans DISENT que rien
 * n'est parti (« messagerie désactivée ») au lieu d'afficher un envoi réussi.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "false", matchIfMissing = true)
public class StubEmailService implements EmailService {

    public enum Kind { RESET, INVITATION }

    private final List<CapturedEmail> captured = new CopyOnWriteArrayList<>();

    @Override
    public void sendPasswordResetEmail(String recipientEmail, String rawResetToken) {
        captured.add(new CapturedEmail(recipientEmail, rawResetToken, Kind.RESET));
        log.info("Stub email: password-reset dispatched to {} (mail.enabled=false; token not logged)", recipientEmail);
    }

    @Override
    public void sendInvitationEmail(String recipientEmail, String rawToken, String prenom, String nom) {
        captured.add(new CapturedEmail(recipientEmail, rawToken, Kind.INVITATION));
        log.info("Stub email: invitation dispatched to {} (mail.enabled=false; token not logged) — "
                + "RIEN n'est parti, l'ecran doit le dire", recipientEmail);
    }

    @Override
    public boolean estSimule() {
        return true;
    }

    /** Returns an immutable snapshot of captured emails for assertions. */
    public List<CapturedEmail> captured() {
        return Collections.unmodifiableList(captured);
    }

    public void clear() {
        captured.clear();
    }

    public record CapturedEmail(String recipientEmail, String rawResetToken, Kind kind) {
        public CapturedEmail(String recipientEmail, String rawResetToken) {
            this(recipientEmail, rawResetToken, Kind.RESET);
        }
    }
}
