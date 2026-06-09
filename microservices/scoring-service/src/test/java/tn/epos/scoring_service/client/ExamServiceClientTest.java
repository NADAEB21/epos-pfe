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
import java.time.LocalDate;
import java.time.LocalTime;
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

    @Nested
    @DisplayName("getExamForGeneration() - lecture cross-service pour la génération")
    class ExamForGeneration {

        private static final String EXAM_HAPPY_BODY =
                "{\"success\":true,\"data\":{" +
                        "\"id\":7," +
                        "\"dateExamen\":\"2026-06-20\"," +
                        "\"heureDebut\":\"08:30\"," +
                        "\"dureeStationMin\":10," +
                        "\"nbEtudiantsParStation\":4," +
                        "\"statut\":\"CONFIGURE\"," +
                        "\"stations\":[" +
                        "{\"id\":10,\"ordre\":1,\"evaluateurIds\":[1000,1001]}," +
                        "{\"id\":11,\"ordre\":2,\"evaluateurIds\":[]}," +
                        "{\"id\":-3,\"ordre\":3,\"evaluateurIds\":[5]}," +   // id<=0 ignoré
                        "{\"id\":12,\"evaluateurIds\":[0,-1,2000]}" +        // ordre null, eval 0/-1 filtrés
                        "]}}";

        @Test
        @DisplayName("Doit parser l'examen + ses stations et forwarder le JWT vers /api/examens/{id}")
        void happyPath_devraitParserExamenEtStations() {
            List<ClientRequest> requests = new ArrayList<>();
            ExamServiceClient client = clientReturning(okJson(EXAM_HAPPY_BODY), requests);

            ExamGenerationView view = client.getExamForGeneration(7L);

            assertThat(view.examenId()).isEqualTo(7L);
            assertThat(view.dateExamen()).isEqualTo(LocalDate.of(2026, 6, 20));
            assertThat(view.heureDebut()).isEqualTo(LocalTime.of(8, 30));
            assertThat(view.dureeStationMin()).isEqualTo(10);
            assertThat(view.nbEtudiantsParStation()).isEqualTo(4);
            assertThat(view.statut()).isEqualTo("CONFIGURE");

            // id<=0 station dropped → 3 stations (10, 11, 12)
            assertThat(view.stations()).hasSize(3);
            var s10 = view.stations().get(0);
            assertThat(s10.id()).isEqualTo(10L);
            assertThat(s10.ordre()).isEqualTo(1);
            assertThat(s10.evaluateurIds()).containsExactly(1000L, 1001L);
            assertThat(view.stations().get(1).evaluateurIds()).isEmpty();
            var s12 = view.stations().get(2);
            assertThat(s12.id()).isEqualTo(12L);
            assertThat(s12.ordre()).isNull();                 // ordre absent → null
            assertThat(s12.evaluateurIds()).containsExactly(2000L); // 0 and -1 filtered

            assertThat(requests).hasSize(1);
            ClientRequest sent = requests.get(0);
            assertThat(sent.url().toString()).contains("/api/examens/7");
            assertThat(sent.headers().getFirst("Authorization")).isEqualTo("Bearer fake-test-token");
        }

        @Test
        @DisplayName("Champs de timing absents + pas de tableau stations → null/empty, pas d'exception")
        void champsAbsents_devraitRetournerNullsEtStationsVides() {
            ExamServiceClient client = clientReturning(
                    okJson("{\"success\":true,\"data\":{\"id\":9,\"statut\":\"BROUILLON\"}}"),
                    new ArrayList<>());

            ExamGenerationView view = client.getExamForGeneration(9L);

            assertThat(view.examenId()).isEqualTo(9L);
            assertThat(view.statut()).isEqualTo("BROUILLON");
            assertThat(view.dateExamen()).isNull();
            assertThat(view.heureDebut()).isNull();
            assertThat(view.dureeStationMin()).isNull();
            assertThat(view.nbEtudiantsParStation()).isNull();
            assertThat(view.stations()).isEmpty();
        }

        @Test
        @DisplayName("Examen introuvable (data sans id) → BusinessException 'introuvable'")
        void examenIntrouvable_devraitLeverBusinessException() {
            ExamServiceClient client = clientReturning(
                    okJson("{\"success\":true,\"data\":{\"message\":\"x\"}}"), new ArrayList<>());

            assertThatThrownBy(() -> client.getExamForGeneration(404L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("introuvable");
        }

        @Test
        @DisplayName("4xx d'exam-service → BusinessException mentionnant le statut")
        void quatreCent_devraitLeverBusinessException() {
            ClientResponse notFound = ClientResponse.create(HttpStatus.NOT_FOUND)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"success\":false,\"message\":\"nope\"}")
                    .build();
            ExamServiceClient client = clientReturning(notFound, new ArrayList<>());

            assertThatThrownBy(() -> client.getExamForGeneration(7L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("exam-service a renvoyé")
                    .hasMessageContaining("404");
        }

        @Test
        @DisplayName("Erreur réseau → BusinessException 'injoignable'")
        void erreurReseau_devraitLeverBusinessException() {
            ExchangeFunction failing = request -> Mono.error(new RuntimeException("connection refused"));
            ExamServiceClient client = new ExamServiceClient(
                    WebClient.builder().exchangeFunction(failing).build());

            assertThatThrownBy(() -> client.getExamForGeneration(7L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("injoignable");
        }
    }
}
