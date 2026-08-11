package tn.epos.scoring_service.service.email;

import tn.epos.scoring_service.dto.ConvocationDTO;

/**
 * Envoi d'une convocation à un étudiant.
 *
 * <p>Même forme que {@code EmailService} de l'auth-service (interface + une
 * implémentation Stub + une implémentation SMTP, choisies par
 * {@code app.mail.enabled}) : deux services qui envoient des e-mails doivent le
 * faire de la même façon, sinon la configuration d'un déploiement devient un
 * piège.
 *
 * <p>L'implémentation compose le corps ; l'appelant ne fabrique pas de texte.
 */
public interface ConvocationEmailService {

    /**
     * Envoie la convocation. Lève une exception si l'envoi échoue — l'appelant
     * transforme ça en une ligne ECHEC pour cet étudiant et continue : un
     * destinataire injoignable ne doit jamais interrompre l'envoi des autres.
     *
     * @param examenNom l'intitulé de l'examen, tel qu'affiché à l'étudiant
     */
    void envoyerConvocation(ConvocationDTO convocation, String examenNom);

    /** true quand rien ne part réellement (mode stub) — l'API le dit à l'écran. */
    boolean estSimule();
}
