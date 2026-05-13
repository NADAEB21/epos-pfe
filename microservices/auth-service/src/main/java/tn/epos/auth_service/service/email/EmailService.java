package tn.epos.auth_service.service.email;

public interface EmailService {

    /**
     * Send a password-reset email to the given recipient.
     * Implementations are responsible for composing the body and resolving the reset URL.
     * The raw token must never be logged or returned in any API response.
     */
    void sendPasswordResetEmail(String recipientEmail, String rawResetToken);
}
