package tn.epos.common.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tn.epos.common.security.revocation.InternalCallAuthenticator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Transport commun des listes {@code /internal/**} rapatriées d'auth-service — extrait de
 * {@code RevocationSyncClient} (#306) quand #303 a eu besoin du MÊME rythme pour les
 * matières retirées : une seule boucle (JDK seul, fil démon, preuve HMAC), deux charges
 * utiles. Les sous-classes ne fournissent que le parsing ({@link #applySnapshot}).
 *
 * <p><b>Volontairement bâti sur le JDK seul</b> ({@code java.net.http} + un exécuteur
 * planifié) : il tourne tel quel dans les services servlet ET dans la gateway WebFlux,
 * sans imposer spring-web ou spring-webflux à {@code epos-common}. Le fil est un démon
 * nommé — il ne retient jamais l'arrêt de la JVM.
 *
 * <p><b>Posture de panne, la partie qui compte.</b>
 * <ul>
 *   <li>Échec de synchronisation → on GARDE la dernière liste reçue et on réessaie au tour
 *       suivant. Ce qui est déjà connu reste appliqué ; auth muet n'a rien de nouveau à
 *       dire (ces listes naissent chez lui).</li>
 *   <li>Aucune synchronisation réussie depuis le démarrage → liste vide, le service SERT.
 *       Refuser de servir ferait dépendre le démarrage de tout service de l'ordre de boot
 *       du compose — sur le PC de la faculté, c'est la panne assurée un matin d'épreuve.
 *       La fenêtre résiduelle est signalée par un WARN à chaque tour raté (le texte de la
 *       conséquence est fourni par la sous-classe : elle seule sait ce qui n'est pas
 *       encore appliqué).</li>
 * </ul>
 */
public abstract class InternalListSyncClient implements AutoCloseable {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final URI endpoint;
    private final String internalAuthHeader;
    private final Duration interval;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;

    /** Étiquette de ticket (« #306 ») + sujet SANS article (« liste de révocation »). */
    private final String issueTag;
    private final String sujet;
    private final String consequenceJamaisSynchronise;

    private volatile boolean everSynced = false;

    protected InternalListSyncClient(String authBaseUrl, String path, String jwtSecret,
                                     Duration interval, String threadName,
                                     String issueTag, String sujet,
                                     String consequenceJamaisSynchronise) {
        this.endpoint = URI.create(stripTrailingSlash(authBaseUrl) + path);
        this.internalAuthHeader = InternalCallAuthenticator.headerValue(jwtSecret);
        this.interval = interval;
        this.issueTag = issueTag;
        this.sujet = sujet;
        this.consequenceJamaisSynchronise = consequenceJamaisSynchronise;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
    }

    /** Remplace l'instantané local depuis le corps HTTP (parsing propre à chaque liste). */
    protected abstract void applySnapshot(String body) throws Exception;

    /** Taille de l'instantané local courant — pour les messages de panne. */
    protected abstract int targetSize();

    /** Démarre la boucle : premier tour immédiat, puis à intervalle fixe. */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::syncOnce, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("{} : synchronisation de la {} démarrée — {} toutes les {} s",
                issueTag, sujet, endpoint, interval.toSeconds());
    }

    /** Un tour de synchronisation. Ne lève JAMAIS — un échec est loggé et réessayé. */
    public final void syncOnce() {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header(InternalCallAuthenticator.HEADER, internalAuthHeader)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                warnFailure("HTTP " + response.statusCode());
                return;
            }

            applySnapshot(response.body());
            if (!everSynced) {
                log.info("{} : première {} reçue — {} entrée(s)", issueTag, sujet, targetSize());
            }
            everSynced = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            warnFailure(e.getMessage());
        }
    }

    private void warnFailure(String cause) {
        if (everSynced) {
            log.warn("{} : synchronisation de la {} ratée ({}) — la dernière liste reçue "
                    + "({} entrée(s)) reste appliquée", issueTag, sujet, cause, targetSize());
        } else {
            log.warn("{} : AUCUNE {} reçue depuis le démarrage ({}) — {}",
                    issueTag, sujet, cause, consequenceJamaisSynchronise);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
