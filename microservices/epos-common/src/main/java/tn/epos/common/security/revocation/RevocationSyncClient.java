package tn.epos.common.security.revocation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * #306 — le poller : rapatrie périodiquement la liste de révocation depuis auth-service et
 * remplace l'instantané local.
 *
 * <p><b>Volontairement bâti sur le JDK seul</b> ({@code java.net.http} + un exécuteur planifié) :
 * il tourne tel quel dans les services servlet (exam, scoring) ET dans la gateway WebFlux, sans
 * imposer spring-web ou spring-webflux à {@code epos-common}. Le fil est un démon nommé — il ne
 * retient jamais l'arrêt de la JVM.
 *
 * <p><b>Posture de panne, la partie qui compte.</b>
 * <ul>
 *   <li>Échec de synchronisation → on GARDE la dernière liste reçue et on réessaie au tour
 *       suivant. Ce qui est déjà connu reste appliqué ; auth muet n'a rien de nouveau à dire
 *       (les révocations naissent chez lui).</li>
 *   <li>Aucune synchronisation réussie depuis le démarrage → liste vide, le service SERT.
 *       Refuser de servir ferait dépendre le démarrage de tout service de l'ordre de boot du
 *       compose — sur le PC de la faculté, c'est la panne assurée un matin d'épreuve. La fenêtre
 *       résiduelle est bornée par la durée de vie du jeton, exactement l'état d'avant #306,
 *       signalée par un WARN à chaque tour raté.</li>
 * </ul>
 */
public final class RevocationSyncClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RevocationSyncClient.class);

    private final URI endpoint;
    private final String internalAuthHeader;
    private final TokenRevocationList target;
    private final Duration interval;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler;

    private volatile boolean everSynced = false;

    public RevocationSyncClient(String authBaseUrl, String jwtSecret,
                                TokenRevocationList target, Duration interval) {
        this.endpoint = URI.create(stripTrailingSlash(authBaseUrl) + "/internal/revocations");
        this.internalAuthHeader = InternalCallAuthenticator.headerValue(jwtSecret);
        this.target = target;
        this.interval = interval;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "revocation-sync");
            t.setDaemon(true);
            return t;
        });
    }

    /** Démarre la boucle : premier tour immédiat, puis à intervalle fixe. */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::syncOnce, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("#306 : synchronisation de la liste de révocation démarrée — {} toutes les {} s",
                endpoint, interval.toSeconds());
    }

    /** Un tour de synchronisation. Ne lève JAMAIS — un échec est loggé et réessayé. */
    void syncOnce() {
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

            Map<Long, Instant> snapshot = parse(response.body());
            target.replaceAll(snapshot);
            if (!everSynced) {
                log.info("#306 : première liste de révocation reçue — {} entrée(s)", snapshot.size());
            }
            everSynced = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            warnFailure(e.getMessage());
        }
    }

    /** Corps attendu : {@code {"success":true,"data":[{"userId":60,"invalidBeforeEpochMs":...}]}}. */
    private Map<Long, Instant> parse(String body) throws Exception {
        JsonNode data = objectMapper.readTree(body).path("data");
        Map<Long, Instant> snapshot = new HashMap<>();
        for (JsonNode row : data) {
            snapshot.put(row.path("userId").asLong(),
                    Instant.ofEpochMilli(row.path("invalidBeforeEpochMs").asLong()));
        }
        return snapshot;
    }

    private void warnFailure(String cause) {
        if (everSynced) {
            log.warn("#306 : synchronisation de révocation ratée ({}) — la dernière liste reçue "
                    + "({} entrée(s)) reste appliquée", cause, target.size());
        } else {
            log.warn("#306 : AUCUNE liste de révocation reçue depuis le démarrage ({}) — les "
                    + "révocations récentes ne sont pas encore appliquées ici (fenêtre bornée par "
                    + "l'expiration des jetons)", cause);
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
