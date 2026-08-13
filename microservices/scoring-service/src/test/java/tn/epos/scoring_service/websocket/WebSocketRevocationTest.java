package tn.epos.scoring_service.websocket;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tn.epos.common.security.revocation.TokenRevocationList;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #306 — la preuve par un VRAI client STOMP, rejouable en CI.
 *
 * <p>⚠️ Le suffixe {@code Test} (et pas {@code IT}) est VOLONTAIRE : la CI lance
 * {@code mvn test}, et les inclusions par défaut de surefire ignorent
 * {@code *IT} en silence — la classe aurait été verte parce que jamais exécutée.
 *
 * <p>Scénarios :
 * <ol>
 *   <li>un CONNECT sans jeton est REFUSÉ (le repli anonyme de BF6.1 est mort) ;</li>
 *   <li>un CONNECT avec jeton dans la frame passe (client à jour) ;</li>
 *   <li>un CONNECT avec jeton SEULEMENT dans la poignée de main HTTP passe — c'est ce qui
 *       garde compatibles les APK déjà installées (leçon #314) ;</li>
 *   <li>un jeton révoqué est refusé À la connexion ;</li>
 *   <li>une session DÉJÀ OUVERTE est débranchée par le balayage quand son porteur est
 *       révoqué — le scénario que #306 nomme « la connexion du jour J ne verra jamais la
 *       révocation », inversé.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WebSocketRevocationTest {

    @LocalServerPort
    private int port;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired
    private TokenRevocationList revocationList;

    @Autowired
    private WebSocketRevocationSweep sweep;

    // ── Aides ────────────────────────────────────────────────────────────────

    /** Jeton signé comme auth-service le ferait (HS384 pour ce secret de 55 octets). */
    private String mintToken(long userId, Instant issuedAt) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("it-user-" + userId + "@epos.tn")
                .claim("userId", userId)
                .claim("authorities", List.of("ROLE_EVALUATEUR"))
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plusSeconds(3600)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS384), claims);
        jwt.sign(new MACSigner(jwtSecret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        return client;
    }

    /** Connexion au transport WebSocket brut de SockJS ({@code /ws/websocket}). */
    private StompSession connect(String frameToken, String handshakeToken) throws Exception {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        if (handshakeToken != null) {
            handshake.add("Authorization", "Bearer " + handshakeToken);
        }
        StompHeaders connectHeaders = new StompHeaders();
        if (frameToken != null) {
            connectHeaders.add("Authorization", "Bearer " + frameToken);
        }
        return stompClient()
                .connectAsync("ws://localhost:" + port + "/ws/websocket",
                        handshake, connectHeaders, new StompSessionHandlerAdapter() { })
                .get(10, TimeUnit.SECONDS);
    }

    private void attendreDeconnexion(StompSession session) throws InterruptedException {
        for (int i = 0; i < 100 && session.isConnected(); i++) {
            Thread.sleep(100);
        }
    }

    // ── Scénarios ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1. CONNECT sans jeton → refusé (le repli anonyme est mort)")
    void connectSansJetonRefuse() {
        assertThatThrownBy(() -> connect(null, null)).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("2. CONNECT client à jour (jeton dans la poignée de main ET la frame) → accepté")
    void connectClientAJourPasse() throws Exception {
        // La poignée de main HTTP traverse la chaîne resource-server ; la frame CONNECT
        // traverse StompConnectAuthenticator. Un client réel présente le jeton aux deux
        // étages — c'est ce que fait l'app mobile après ce correctif.
        String token = mintToken(9101L, Instant.now());
        StompSession session = connect(token, token);
        assertThat(session.isConnected()).isTrue();
        session.disconnect();
    }

    @Test
    @DisplayName("3. CONNECT avec jeton SEULEMENT dans la poignée de main → accepté (APK déjà installées)")
    void connectPoigneeDeMainPasse() throws Exception {
        StompSession session = connect(null, mintToken(9102L, Instant.now()));
        assertThat(session.isConnected()).isTrue();
        session.disconnect();
    }

    @Test
    @DisplayName("4. jeton révoqué → refusé À la connexion (RevocationAwareJwtDecoder)")
    void jetonRevoqueRefuseAuConnect() throws Exception {
        Instant emission = Instant.now().minusSeconds(60);
        String token = mintToken(9103L, emission);
        revocationList.put(9103L, emission.plusSeconds(30));

        assertThatThrownBy(() -> connect(token, null)).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("5. session OUVERTE puis porteur révoqué → le balayage la débranche")
    void sessionOuverteDebrancheeParLeBalayage() throws Exception {
        Instant emission = Instant.now().minusSeconds(60);
        String token = mintToken(9104L, emission);
        StompSession session = connect(token, token);
        assertThat(session.isConnected()).isTrue();

        // L'administrateur révoque — la liste l'apprend (ici sans attendre le poller).
        revocationList.put(9104L, Instant.now());
        sweep.sweep();

        attendreDeconnexion(session);
        assertThat(session.isConnected())
                .as("la session du jour J doit être débranchée par le balayage, "
                        + "pas survivre jusqu'à l'expiration du jeton")
                .isFalse();
    }

    @Test
    @DisplayName("5bis. discriminant — le balayage ne débranche PAS les autres sessions")
    void balayageNeDebranchePasLesAutres() throws Exception {
        String token = mintToken(9105L, Instant.now());
        StompSession innocente = connect(token, token);
        revocationList.put(9106L, Instant.now());

        sweep.sweep();
        Thread.sleep(300);

        assertThat(innocente.isConnected()).isTrue();
        innocente.disconnect();
    }
}
