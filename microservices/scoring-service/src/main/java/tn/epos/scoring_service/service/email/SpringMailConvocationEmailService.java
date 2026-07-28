package tn.epos.scoring_service.service.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import tn.epos.scoring_service.dto.ConvocationDTO;

import java.time.format.DateTimeFormatter;

/**
 * Envoi réel, actif uniquement si {@code app.mail.enabled=true}.
 *
 * <p>Injection par constructeur (et non {@code @Value} sur des champs) pour
 * rester testable avec un {@link JavaMailSender} mocké, sans contexte Spring —
 * même choix que l'auth-service.
 *
 * <p>Le corps est écrit pour l'ÉTUDIANT, pas pour le système : pas de « lot
 * n° 2 / statut CONFIGURE », mais la date, l'heure à laquelle se présenter et
 * ce qui l'attend. Il ne promet que ce qui est vrai au moment de l'envoi —
 * l'ordre de passage par station est tiré sur place, après l'appel.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
public class SpringMailConvocationEmailService implements ConvocationEmailService {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SpringMailConvocationEmailService(
            JavaMailSender mailSender,
            @Value("${app.mail.from:noreply@epos.tn}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void envoyerConvocation(ConvocationDTO c, String examenNom) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(fromAddress);
        msg.setTo(c.email());
        msg.setSubject("Convocation — " + examenNom);
        msg.setText("""
                Bonjour %s %s,

                Vous êtes convoqué(e) à l'examen pratique « %s ».

                  Date          : %s
                  Heure         : %s
                  Groupe (lot)  : %d
                  N° inscription: %s

                Merci de vous présenter 15 minutes avant l'heure indiquée.
                L'ordre de passage entre les stations vous sera communiqué sur place,
                après l'appel.

                Cet e-mail est envoyé automatiquement, merci de ne pas y répondre.
                """.formatted(
                        c.prenom() == null ? "" : c.prenom(),
                        c.nom() == null ? "" : c.nom(),
                        examenNom,
                        c.jour() == null ? "—" : c.jour().format(JOUR),
                        c.heureConvocation() == null ? "—" : c.heureConvocation(),
                        c.lotNumero() == null ? 0 : c.lotNumero(),
                        c.numero_inscription() == null ? "—" : c.numero_inscription()));

        mailSender.send(msg);
        // L'adresse est loguée (c'est une donnée de convocation, pas un secret),
        // jamais le corps.
        log.info("Convocation envoyée à {} (examen « {} », lot {})",
                c.email(), examenNom, c.lotNumero());
    }

    @Override
    public boolean estSimule() {
        return false;
    }
}
