package tn.epos.auth_service.service.email;

public interface EmailService {

    /**
     * Send a password-reset email to the given recipient.
     * Implementations are responsible for composing the body and resolving the reset URL.
     * The raw token must never be logged or returned in any API response.
     */
    void sendPasswordResetEmail(String recipientEmail, String rawResetToken);

    /**
     * #389 — e-mail d'invitation à la création d'un compte : « votre compte est
     * créé, choisissez votre mot de passe ». Porte un lien ET le code brut à
     * copier-coller (même raison que la réinitialisation : pas de deep-linking
     * mobile). Le jeton est un {@code PasswordResetToken} à validité longue
     * (7 jours), à usage unique — jamais un mot de passe en clair dans une
     * boîte mail.
     */
    void sendInvitationEmail(String recipientEmail, String rawToken, String prenom, String nom);

    /**
     * {@code true} quand RIEN ne part (stub, {@code app.mail.enabled=false}).
     * Même contrat que {@code ConvocationEmailService.estSimule()} côté scoring :
     * l'écran doit pouvoir dire « messagerie désactivée » au lieu d'un toast
     * vert sur un envoi qui n'a pas eu lieu.
     */
    boolean estSimule();
}
