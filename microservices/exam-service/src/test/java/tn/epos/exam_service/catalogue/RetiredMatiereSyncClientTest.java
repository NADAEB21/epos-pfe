package tn.epos.exam_service.catalogue;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tn.epos.common.security.revocation.InternalCallAuthenticator;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #303 — le poller des matières retirées contre un VRAI serveur HTTP (celui du JDK), calqué
 * sur le test du poller de révocation #306 : tour nominal + preuve HMAC, posture de panne
 * « on garde la dernière liste », et sémantique d'INSTANTANÉ (une matière réactivée disparaît
 * de la liste et redevient utilisable — le retrait #134 est réversible).
 */
class RetiredMatiereSyncClientTest {

    private static final String SECRET = "un-secret-de-test-suffisamment-long-32o";

    private HttpServer server;
    private final AtomicReference<String> presentedHeader = new AtomicReference<>();
    private final AtomicReference<String> bodyToServe = new AtomicReference<>(
            "{\"success\":true,\"data\":[{\"id\":10,\"libelle\":\"Pharmacognosie\"}]}");
    private final AtomicReference<Integer> statusToServe = new AtomicReference<>(200);

    private final RetiredMatiereList list = new RetiredMatiereList();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/matieres-retirees", exchange -> {
            presentedHeader.set(exchange.getRequestHeaders().getFirst(InternalCallAuthenticator.HEADER));
            byte[] body = bodyToServe.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusToServe.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private RetiredMatiereSyncClient client() {
        return new RetiredMatiereSyncClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                SECRET, list, Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("un tour : la liste est remplacée (id + libellé) et la preuve HMAC accompagne l'appel")
    void tourNominal() {
        try (RetiredMatiereSyncClient client = client()) {
            client.syncOnce();
        }

        assertThat(presentedHeader.get()).isEqualTo(InternalCallAuthenticator.headerValue(SECRET));
        assertThat(list.isRetired(10L)).isTrue();
        assertThat(list.libelleOf(10L)).isEqualTo("Pharmacognosie");
        assertThat(list.isRetired(1L)).isFalse();
    }

    @Test
    @DisplayName("panne (HTTP 500) : la DERNIÈRE liste reçue reste appliquée")
    void panneGardeLaDerniereListe() {
        list.replaceAll(Map.of(10L, "Pharmacognosie"));
        statusToServe.set(500);

        try (RetiredMatiereSyncClient client = client()) {
            client.syncOnce();
        }

        assertThat(list.isRetired(10L)).isTrue();
    }

    @Test
    @DisplayName("auth injoignable : idem — l'échec est loggé, la liste tient")
    void injoignableGardeLaListe() {
        list.replaceAll(Map.of(10L, "Pharmacognosie"));
        server.stop(0);

        try (RetiredMatiereSyncClient client = new RetiredMatiereSyncClient(
                "http://127.0.0.1:1", SECRET, list, Duration.ofMinutes(5))) {
            client.syncOnce();
        }

        assertThat(list.isRetired(10L)).isTrue();
    }

    @Test
    @DisplayName("une matière RÉACTIVÉE sort de l'instantané et redevient utilisable (#134 réversible)")
    void instantanePasCumul() {
        list.replaceAll(Map.of(999L, "Ancienne"));

        try (RetiredMatiereSyncClient client = client()) {
            client.syncOnce();
        }

        assertThat(list.isRetired(999L)).isFalse();
        assertThat(list.isRetired(10L)).isTrue();
    }

    @Test
    @DisplayName("libellé inconnu → repli honnête sur l'id, jamais null")
    void libelleInconnuRepliSurId() {
        assertThat(list.libelleOf(42L)).isEqualTo("matière #42");
    }
}
