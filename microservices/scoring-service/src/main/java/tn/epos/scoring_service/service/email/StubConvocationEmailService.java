package tn.epos.scoring_service.service.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tn.epos.scoring_service.dto.ConvocationDTO;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Implémentation par défaut : capture les envois en mémoire, n'envoie RIEN.
 *
 * <p>C'est le comportement par défaut ({@code matchIfMissing = true}) et c'est
 * délibéré : un déploiement de démo, un {@code docker compose up} sur une copie
 * de la base réelle ou un test enverraient sinon de vrais e-mails à de vrais
 * étudiants. Passer en envoi réel doit être un acte explicite
 * ({@code MAIL_ENABLED=true} + un SMTP configuré), jamais un oubli.
 *
 * <p>{@link #estSimule()} remonte jusqu'à l'écran, pour que « envoyé » ne
 * s'affiche jamais quand rien n'est parti.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "false", matchIfMissing = true)
public class StubConvocationEmailService implements ConvocationEmailService {

    private final List<ConvocationEnvoyee> captured = new CopyOnWriteArrayList<>();

    @Override
    public void envoyerConvocation(ConvocationDTO c, String examenNom) {
        captured.add(new ConvocationEnvoyee(c.email(), examenNom, c.lotNumero(),
                c.jour() == null ? null : c.jour().toString(), c.heureConvocation()));
        log.info("Stub e-mail : convocation simulée pour {} (examen « {} », lot {}, {} à {}) "
                        + "— app.mail.enabled=false, rien n'a été envoyé",
                c.email(), examenNom, c.lotNumero(), c.jour(), c.heureConvocation());
    }

    @Override
    public boolean estSimule() {
        return true;
    }

    /** Instantané immuable, pour les assertions de test. */
    public List<ConvocationEnvoyee> captured() {
        return Collections.unmodifiableList(captured);
    }

    public void clear() {
        captured.clear();
    }

    public record ConvocationEnvoyee(String email, String examenNom, Integer lotNumero,
                                     String jour, String heure) {}
}
