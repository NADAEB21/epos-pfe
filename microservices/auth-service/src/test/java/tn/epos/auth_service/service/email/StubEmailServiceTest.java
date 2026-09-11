package tn.epos.auth_service.service.email;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that the stub email provider captures dispatch calls with the
 * recipient + raw token, so other tests can assert what AuthService sent.
 * This is the "integration test verifies stub was called with expected args"
 * contract from issue #9 — kept as a focused unit test until the full Spring
 * Boot integration-test harness lands in Sprint 2 (#28).
 */
class StubEmailServiceTest {

    @Test
    void sendPasswordResetEmail_capturesRecipientAndRawToken() {
        StubEmailService svc = new StubEmailService();

        svc.sendPasswordResetEmail("eval@epos.tn", "raw-token-xyz");

        assertThat(svc.captured()).hasSize(1);
        StubEmailService.CapturedEmail entry = svc.captured().get(0);
        assertThat(entry.recipientEmail()).isEqualTo("eval@epos.tn");
        assertThat(entry.rawResetToken()).isEqualTo("raw-token-xyz");
    }

    @Test
    void sendInvitationEmail_capturesAsInvitation_andSaysItIsSimulated() {
        // #389 — le stub « envoie » dans le vide et le DIT : l'ecran ne doit
        // jamais afficher « invitation envoyee » sur cette implementation.
        StubEmailService svc = new StubEmailService();

        svc.sendInvitationEmail("rania@epos.tn", "raw-invite", "Rania", "Aouina");

        assertThat(svc.estSimule()).isTrue();
        assertThat(svc.captured()).hasSize(1);
        StubEmailService.CapturedEmail entry = svc.captured().get(0);
        assertThat(entry.kind()).isEqualTo(StubEmailService.Kind.INVITATION);
        assertThat(entry.recipientEmail()).isEqualTo("rania@epos.tn");
        assertThat(entry.rawResetToken()).isEqualTo("raw-invite");
    }

    @Test
    void sendPasswordResetEmail_isCapturedAsReset() {
        StubEmailService svc = new StubEmailService();
        svc.sendPasswordResetEmail("eval@epos.tn", "t");
        assertThat(svc.captured().get(0).kind()).isEqualTo(StubEmailService.Kind.RESET);
    }

    @Test
    void captured_returnsImmutableView() {
        StubEmailService svc = new StubEmailService();
        svc.sendPasswordResetEmail("a@b.com", "t");

        assertThatThrownBy(() -> svc.captured().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void clear_emptiesCapture() {
        StubEmailService svc = new StubEmailService();
        svc.sendPasswordResetEmail("a@b.com", "t");

        svc.clear();

        assertThat(svc.captured()).isEmpty();
    }
}
