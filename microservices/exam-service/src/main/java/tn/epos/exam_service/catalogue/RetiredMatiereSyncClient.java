package tn.epos.exam_service.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tn.epos.common.security.revocation.InternalCallAuthenticator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * #303 — rapatrie périodiquement les matières RETIRÉES depuis auth-service
 * ({@code /internal/matieres-retirees}) et remplace l'instantané local.
 *
 * <p>Calqué délibérément sur {@code RevocationSyncClient} (#306) : JDK seul, fil démon,
 * preuve HMAC via {@link InternalCallAuthenticator}. Une seule différence de fond — la
 * fenêtre tolérée : une révocation de jeton est un acte de sécurité (fenêtre à minimiser),
 * une fermeture de matière est un acte de catalogue (30 s ne changent rien).
 *
 * <p><b>Posture de panne (identique à #306).</b> Échec de synchronisation → la dernière
 * liste reçue reste appliquée (les fermetures naissent chez auth : muet, il n'a rien de
 * nouveau à dire). Aucune synchronisation depuis le démarrage → liste vide, le service
 * SERT — le démarrage d'exam-service ne dépend jamais de l'ordre de boot du compose ;
 * la fenêtre résiduelle est signalée par un WARN à chaque tour raté.
 */
public final class RetiredMatiereSyncClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RetiredMatiereSyncClient.class);

    private final URI endpoint;
    private final String internalAuthHeader;
    private final RetiredMatiereList target;
    private final Duration interval;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler;

    private volatile boolean everSynced = false;

    public RetiredMatiereSyncClient(String authBaseUrl, String jwtSecret,
                                    RetiredMatiereList target, Duration interval) {
        this.endpoint = URI.create(stripTrailingSlash(authBaseUrl) + "/internal/matieres-retirees");
        this.internalAuthHeader = InternalCallAuthenticator.headerValue(jwtSecret);
        this.target = target;
        this.interval = interval;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "matiere-retiree-sync");
            t.setDaemon(true);
            return t;
        });
    }

    /** Démarre la boucle : premier tour immédiat, puis à intervalle fixe. */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::syncOnce, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("#303 : synchronisation des matières retirées démarrée — {} toutes les {} s",
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

            Map<Long, String> snapshot = parse(response.body());
            target.replaceAll(snapshot);
            if (!everSynced) {
                log.info("#303 : première liste de matières retirées reçue — {} entrée(s)",
                        snapshot.size());
            }
            everSynced = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            warnFailure(e.getMessage());
        }
    }

    /** Corps attendu : {@code {"success":true,"data":[{"id":10,"libelle":"Pharmacognosie"}]}}. */
    private Map<Long, String> parse(String body) throws Exception {
        JsonNode data = objectMapper.readTree(body).path("data");
        Map<Long, String> snapshot = new HashMap<>();
        for (JsonNode row : data) {
            snapshot.put(row.path("id").asLong(), row.path("libelle").asText(""));
        }
        return snapshot;
    }

    private void warnFailure(String cause) {
        if (everSynced) {
            log.warn("#303 : synchronisation des matières retirées ratée ({}) — la dernière liste "
                    + "reçue ({} entrée(s)) reste appliquée", cause, target.size());
        } else {
            log.warn("#303 : AUCUNE liste de matières retirées reçue depuis le démarrage ({}) — "
                    + "les fermetures récentes du catalogue ne sont pas encore appliquées ici",
                    cause);
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
