package tn.epos.common.security.revocation;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #306 — le poller contre un VRAI serveur HTTP (celui du JDK) : le tour de synchronisation,
 * la preuve d'authentification interne, et la posture de panne « on garde la dernière liste ».
 */
class RevocationSyncClientTest {

    private static final String SECRET = "un-secret-de-test-suffisamment-long-32o";
    private static final Instant STAMP = Instant.parse("2026-08-13T10:00:00Z");

    private HttpServer server;
    private final AtomicReference<String> presentedHeader = new AtomicReference<>();
    private final AtomicReference<String> bodyToServe = new AtomicReference<>(
            "{\"success\":true,\"data\":[{\"userId\":60,\"invalidBeforeEpochMs\":" + STAMP.toEpochMilli() + "}]}");
    private final AtomicReference<Integer> statusToServe = new AtomicReference<>(200);

    private final TokenRevocationList list = new TokenRevocationList();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/revocations", exchange -> {
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

    private RevocationSyncClient client() {
        return new RevocationSyncClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                SECRET, list, Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("un tour : la liste est remplacée et la preuve HMAC accompagne l'appel")
    void tourNominal() {
        try (RevocationSyncClient client = client()) {
            client.syncOnce();
        }

        assertThat(presentedHeader.get()).isEqualTo(InternalCallAuthenticator.headerValue(SECRET));
        assertThat(list.isRevoked(60L, STAMP.minusSeconds(1))).isTrue();
        assertThat(list.isRevoked(60L, STAMP.plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("panne (HTTP 500) : la DERNIÈRE liste reçue reste appliquée — jamais de fail-open")
    void panneGardeLaDerniereListe() {
        list.replaceAll(Map.of(60L, STAMP));
        statusToServe.set(500);

        try (RevocationSyncClient client = client()) {
            client.syncOnce();
        }

        assertThat(list.isRevoked(60L, STAMP.minusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("auth injoignable : idem — l'échec est loggé, la liste tient")
    void injoignableGardeLaListe() {
        list.replaceAll(Map.of(60L, STAMP));
        server.stop(0);

        try (RevocationSyncClient client = new RevocationSyncClient(
                "http://127.0.0.1:1", SECRET, list, Duration.ofMinutes(5))) {
            client.syncOnce();
        }

        assertThat(list.isRevoked(60L, STAMP.minusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("une entrée disparue du corps cesse de révoquer — la liste est un instantané, pas un cumul")
    void instantanePasCumul() {
        list.replaceAll(Map.of(999L, STAMP));
        try (RevocationSyncClient client = client()) {
            client.syncOnce();
        }

        assertThat(list.isRevoked(999L, STAMP.minusSeconds(1))).isFalse();
        assertThat(list.isRevoked(60L, STAMP.minusSeconds(1))).isTrue();
    }
}
