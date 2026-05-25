package tn.epos.scoring_service.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tn.epos.common.exception.BusinessException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ExamServiceClient - Tests unitaires")
class ExamServiceClientTest {

    private static final String GRILLE_ITEMS_HAPPY_BODY =
            "{\"success\":true,\"data\":{\"content\":[" +
                    "{\"id\":100,\"libelle\":\"x\"}," +
                    "{\"id\":101,\"libelle\":\"y\"}" +
                    "]}}";

    @BeforeEach
    void primeJwtInContext() {
        Jwt jwt = Jwt.withTokenValue("fake-test-token")
                .header("alg", "HS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .subject("user@test.tn")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Build an ExamServiceClient backed by a WebClient that returns the given response. */
    private static ExamServiceClient clientReturning(ClientResponse response, List<ClientRequest> capturedRequests) {
        ExchangeFunction exchange = request -> {
            capturedRequests.add(request);
            return Mono.just(response);
        };
        WebClient webClient = WebClient.builder().exchangeFunction(exchange).build();
        return new ExamServiceClient(webClient);
    }

    private static ClientResponse okJson(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    @Nested
    @DisplayName("getItemIdsForGrille() - happy path & cache")
    class HappyPath {

        @Test
        @DisplayName("Doit appeler exam-service et retourner les item IDs parsés")
        void happyPath_devraitParserLesItemIds() {
            List<ClientRequest> requests = new ArrayList<>();
            ExamServiceClient client = clientReturning(okJson(GRILLE_ITEMS_HAPPY_BODY), requests);

            Set<Long> ids = client.getItemIdsForGrille(11L);

            assertThat(ids).containsExactlyInAnyOrder(100L, 101L);
            assertThat(requests).hasSize(1);
            ClientRequest sent = requests.get(0);
            assertThat(sent.url().toString())
                    .contains("/api/grilles/11/items")
                    .contains("size=500");
            assertThat(sent.headers().getFirst("Authorization"))
                    .isEqualTo("Bearer fake-test-token");
        }

        @Test
        @DisplayName("Le second appel doit servir le cache (pas de HTTP)")
        void cacheHit_devraitEviterHttp() {
            AtomicInteger calls = new AtomicInteger();
            ExchangeFunction counting = request -> {
                calls.incrementAndGet();
                return Mono.just(okJson(GRILLE_ITEMS_HAPPY_BODY));
            };
            ExamServiceClient client = new ExamServiceClient(
                    WebClient.builder().exchangeFunction(counting).build());

            client.getItemIdsForGrille(11L);
            client.getItemIdsForGrille(11L);
            client.getItemIdsForGrille(11L);

            assertThat(calls).hasValue(1);
        }

        @Test
        @DisplayName("Réponse mal formée (pas de data.content) → set vide, pas d'exception")
        void responseMalformed_devraitRetournerSetVide() {
            ExamServiceClient client = clientReturning(
                    okJson("{\"success\":true,\"data\":{}}"), new ArrayList<>());

            Set<Long> ids = client.getItemIdsForGrille(42L);

            assertThat(ids).isEmpty();
        }

        @Test
        @DisplayName("Items avec id manquant ou invalide sont ignorés")
        void itemsAvecIdInvalides_doiventEtreFiltres() {
            String body = "{\"data\":{\"content\":[" +
                    "{\"id\":100}," +
                    "{\"libelle\":\"no-id\"}," +
                    "{\"id\":-5}," +
                    "{\"id\":0}," +
                    "{\"id\":200}" +
                    "]}}";
            ExamServiceClient client = clientReturning(okJson(body), new ArrayList<>());

            Set<Long> ids = client.getItemIdsForGrille(11L);

            assertThat(ids).containsExactlyInAnyOrder(100L, 200L);
        }
    }

    @Nested
    @DisplayName("getItemIdsForGrille() - erreurs (fail-closed)")
    class Errors {

        @Test
        @DisplayName("4xx d'exam-service → BusinessException mentionnant le statut")
        void quatreCent_devraitLeverBusinessException() {
            ClientResponse forbidden = ClientResponse.create(HttpStatus.FORBIDDEN)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"success\":false,\"message\":\"nope\"}")
                    .build();
            ExamServiceClient client = clientReturning(forbidden, new ArrayList<>());

            assertThatThrownBy(() -> client.getItemIdsForGrille(11L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("exam-service a renvoyé")
                    .hasMessageContaining("403");
        }

        @Test
        @DisplayName("Erreur réseau (Mono.error) → BusinessException 'injoignable'")
        void erreurReseau_devraitLeverBusinessException() {
            ExchangeFunction failing = request ->
                    Mono.error(new RuntimeException("connection refused"));
            ExamServiceClient client = new ExamServiceClient(
                    WebClient.builder().exchangeFunction(failing).build());

            assertThatThrownBy(() -> client.getItemIdsForGrille(11L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("injoignable");
        }

        @Test
        @DisplayName("Aucun JWT dans le contexte → BusinessException sans appel HTTP")
        void aucunJwt_devraitLeverBusinessException() {
            SecurityContextHolder.clearContext();
            AtomicInteger calls = new AtomicInteger();
            ExchangeFunction counting = request -> {
                calls.incrementAndGet();
                return Mono.just(okJson(GRILLE_ITEMS_HAPPY_BODY));
            };
            ExamServiceClient client = new ExamServiceClient(
                    WebClient.builder().exchangeFunction(counting).build());

            assertThatThrownBy(() -> client.getItemIdsForGrille(11L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("aucun JWT");
            assertThat(calls).hasValue(0);
        }
    }
}
