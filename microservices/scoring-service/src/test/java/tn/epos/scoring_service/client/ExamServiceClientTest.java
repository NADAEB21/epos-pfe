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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ExamServiceClient}.
 * Covers: getItemIdsForGrille, getItemInfosForGrille, getStationInfo,
 * getExamForGeneration (happy path + error branches).
 */
@DisplayName("ExamServiceClient — tests unitaires")
class ExamServiceClientTest {
    private static final String GRILLE_ITEMS_HAPPY_BODY =
            "{\"success\":true,\"data\":[" +
                    "{\"id\":100,\"libelle\":\"x\",\"ponderation\":2.0,\"type\":\"BINAIRE\"}," +
                    "{\"id\":101,\"libelle\":\"y\",\"ponderation\":5.0,\"type\":\"NUMERIQUE\"}" +
                    "]}";

    @BeforeEach
    void primeJwtInContext() {
        Jwt jwt = Jwt.withTokenValue("fake-test-token")
                .header("alg", "HS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .subject("user@test.tn")
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static ExamServiceClient clientReturning(ClientResponse response,
                                                     List<ClientRequest> captured) {
        ExchangeFunction exchange = request -> {
            captured.add(request);
            return Mono.just(response);
        };
        return new ExamServiceClient(WebClient.builder().exchangeFunction(exchange).build());
    }

    private static ClientResponse okJson(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }

    // =========================================================================
    // getItemIdsForGrille / getItemInfosForGrille — happy path & cache
    // =========================================================================

    @Nested
    @DisplayName("getItemIdsForGrille() — happy path & cache")
    class HappyPath {
        @Test
        @DisplayName("Parse les item IDs et ajoute le header Authorization")
        void happyPath_parseItemIds() {
            List<ClientRequest> requests = new ArrayList<>();
            ExamServiceClient client = clientReturning(okJson(GRILLE_ITEMS_HAPPY_BODY), requests);

            Set<Long> ids = client.getItemIdsForGrille(11L);

            assertThat(ids).containsExactlyInAnyOrder(100L, 101L);
            assertThat(requests).hasSize(1);
            assertThat(requests.get(0).url().toString()).contains("/api/grilles/11/items/feuilles");
            assertThat(requests.get(0).headers().getFirst("Authorization"))
                    .isEqualTo("Bearer fake-test-token");
        }

        @Test
        @DisplayName("getItemInfosForGrille() remplit pondération et type")
        void getItemInfos_pondérationEtType() {
            ExamServiceClient client = clientReturning(okJson(GRILLE_ITEMS_HAPPY_BODY), new ArrayList<>());

            Map<Long, ExamServiceClient.ItemInfo> infos = client.getItemInfosForGrille(11L);

            assertThat(infos).containsKey(100L);
            assertThat(infos.get(100L).ponderation()).isEqualTo(2.0);
            assertThat(infos.get(100L).type()).isEqualTo("BINAIRE");
            assertThat(infos.get(101L).ponderation()).isEqualTo(5.0);
            assertThat(infos.get(101L).type()).isEqualTo("NUMERIQUE");
        }

        @Test
        @DisplayName("Valeur par défaut ponderation=1.0 et type=BINAIRE si champs absents")
        void getItemInfos_defaultValues() {
            String body = "{\"data\":[{\"id\":200}]}";
            ExamServiceClient client = clientReturning(okJson(body), new ArrayList<>());

            Map<Long, ExamServiceClient.ItemInfo> infos = client.getItemInfosForGrille(50L);

            assertThat(infos.get(200L).ponderation()).isEqualTo(1.0);
            assertThat(infos.get(200L).type()).isEqualTo("BINAIRE");
        }

        @Test
        @DisplayName("Le cache évite les appels HTTP répétés")
        void cacheHit_eviteHttp() {
            AtomicInteger calls = new AtomicInteger();
            ExchangeFunction counting = req -> {
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
        @DisplayName("Réponse sans data.content → set vide, pas d'exception")
        void responseMalformed_retourneSetVide() {
            ExamServiceClient client = clientReturning(
                    okJson("{\"success\":true,\"data\":{}}"), new ArrayList<>());

            assertThat(client.getItemIdsForGrille(42L)).isEmpty();
        }

        @Test
        @DisplayName("Items avec id ≤ 0 ou absent sont ignorés")
        void itemsIdInvalides_filtres() {
            String body = "{\"data\":[" +
                    "{\"id\":100}," +
                    "{\"libelle\":\"no-id\"}," +
                    "{\"id\":-5}," +
                    "{\"id\":0}," +
                    "{\"id\":200}" +
                    "]}";
            ExamServiceClient client = clientReturning(okJson(body), new ArrayList<>());

            assertThat(client.getItemIdsForGrille(11L)).containsExactlyInAnyOrder(100L, 200L);
        }
    }

    // =========================================================================
    // getItemIdsForGrille — erreurs
    // =========================================================================

    @Nested
    @DisplayName("getItemIdsForGrille() — erreurs")
    class ItemIdsErrors {

        @Test
        @DisplayName("4xx → BusinessException avec le statut")
        void quatreCent_BusinessException() {
            ClientResponse forbidden = ClientResponse.create(HttpStatus.FORBIDDEN)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"success\":false}")
                    .build();
            ExamServiceClient client = clientReturning(forbidden, new ArrayList<>());

            assertThatThrownBy(() -> client.getItemIdsForGrille(11L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("403");
        }

        @Test
        @DisplayName("Erreur réseau → BusinessException 'injoignable'")
        void erreurReseau_BusinessException() {
            ExchangeFunction failing = req -> Mono.error(new RuntimeException("refused"));
            ExamServiceClient client = new ExamServiceClient(
                    WebClient.builder().exchangeFunction(failing).build());

            assertThatThrownBy(() -> client.getItemIdsForGrille(11L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("injoignable");
        }

        @Test
        @DisplayName("Pas de JWT → BusinessException sans appel HTTP")
        void aucunJwt_BusinessException() {
            SecurityContextHolder.clearContext();
            AtomicInteger calls = new AtomicInteger();
            ExchangeFunction counting = req -> {
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

    // =========================================================================
    // getStationInfo
    // =========================================================================

    @Nested
    @DisplayName("getStationInfo()")
    class GetStationInfo {

        @Test
        @DisplayName("Retourne le nom de la station depuis data.nom")
        void happyPath_retourneNom() {
            String body = "{\"data\":{\"nom\":\"Station Chimie\"}}";
            ExamServiceClient client = clientReturning(okJson(body), new ArrayList<>());

            ExamServiceClient.StationInfo info = client.getStationInfo(7L);

            assertThat(info.nom()).isEqualTo("Station Chimie");
        }

        @Test
        @DisplayName("data.nom absent → nom de repli 'Station {id}'")
        void nomAbsent_fallback() {
            String body = "{\"data\":{}}";
            ExamServiceClient client = clientReturning(okJson(body), new ArrayList<>());

            ExamServiceClient.StationInfo info = client.getStationInfo(7L);

            assertThat(info.nom()).isEqualTo("Station 7");
        }

        @Test
        @DisplayName("Réponse root null → nom de repli 'Station {id}'")
        void responseNull_fallback() {
            // Le WebClient retourne un body vide → Jackson → null root
            ClientResponse nullBody = ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("null")
                    .build();
            ExamServiceClient client = clientReturning(nullBody, new ArrayList<>());

            ExamServiceClient.StationInfo info = client.getStationInfo(3L);

            assertThat(info.nom()).isEqualTo("Station 3");
        }

        @Test
        @DisplayName("Erreur réseau → nom de repli 'Station {id}', pas d'exception")
        void erreurReseau_fallback() {
            ExchangeFunction failing = req -> Mono.error(new RuntimeException("timeout"));
            ExamServiceClient client = new ExamServiceClient(
                    WebClient.builder().exchangeFunction(failing).build());

            ExamServiceClient.StationInfo info = client.getStationInfo(5L);

            assertThat(info.nom()).isEqualTo("Station 5");
        }

        @Test
        @DisplayName("Le header Authorization est transmis à /api/stations/{id}")
        void forwardeBearerToken() {
            List<ClientRequest> requests = new ArrayList<>();
            String body = "{\"data\":{\"nom\":\"S1\"}}";
            ExamServiceClient client = clientReturning(okJson(body), requests);

            client.getStationInfo(10L);

            assertThat(requests).hasSize(1);
            assertThat(requests.get(0).url().toString()).contains("/api/stations/10");
            assertThat(requests.get(0).headers().getFirst("Authorization"))
                    .isEqualTo("Bearer fake-test-token");
        }
    }

    // =========================================================================
    // getExamForGeneration
    // =========================================================================

    @Nested
    @DisplayName("getExamForGeneration()")
    class ExamForGeneration {

        private static final String EXAM_HAPPY_BODY =
                "{\"success\":true,\"data\":{" +
                        "\"id\":7," +
                        "\"dateExamen\":\"2026-06-20\"," +
                        "\"heureDebut\":\"08:30\"," +
                        "\"dureeStationMin\":10," +
                        "\"tempsBattementMin\":5," +
                        "\"nbEtudiantsParStation\":4," +
                        "\"statut\":\"CONFIGURE\"," +
                        "\"stations\":[" +
                        "{\"id\":10,\"ordre\":1,\"evaluateurIds\":[1000,1001]}," +
                        "{\"id\":11,\"ordre\":2,\"evaluateurIds\":[]}," +
                        "{\"id\":-3,\"ordre\":3,\"evaluateurIds\":[5]}," +
                        "{\"id\":12,\"evaluateurIds\":[0,-1,2000]}" +
                        "]}}";

        @Test
        @DisplayName("Parse l'examen, ses stations, et transmet le JWT")
        void happyPath_parseExamen() {
            List<ClientRequest> requests = new ArrayList<>();
            ExamServiceClient client = clientReturning(okJson(EXAM_HAPPY_BODY), requests);

            ExamGenerationView view = client.getExamForGeneration(7L);

            assertThat(view.examenId()).isEqualTo(7L);
            assertThat(view.dateExamen()).isEqualTo(LocalDate.of(2026, 6, 20));
            assertThat(view.heureDebut()).isEqualTo(LocalTime.of(8, 30));
            assertThat(view.dureeStationMin()).isEqualTo(10);
            assertThat(view.tempsBattementMin()).isEqualTo(5);   // ADR-0012
            assertThat(view.nbEtudiantsParStation()).isEqualTo(4);
            assertThat(view.statut()).isEqualTo("CONFIGURE");
            assertThat(view.launchedAt()).isNull();

            assertThat(view.stations()).hasSize(3);
            assertThat(view.stations().get(0).evaluateurIds()).containsExactly(1000L, 1001L);
            assertThat(view.stations().get(2).evaluateurIds()).containsExactly(2000L);

            assertThat(requests.get(0).url().toString()).contains("/api/examens/7");
            assertThat(requests.get(0).headers().getFirst("Authorization"))
                    .isEqualTo("Bearer fake-test-token");
        }

        @Test
        @DisplayName("Champs timing absents → null / stations vides, pas d'exception")
        void champsAbsents_nullsEtStationsVides() {
            ExamServiceClient client = clientReturning(
                    okJson("{\"success\":true,\"data\":{\"id\":9,\"statut\":\"BROUILLON\"}}"),
                    new ArrayList<>());

            ExamGenerationView view = client.getExamForGeneration(9L);

            assertThat(view.dateExamen()).isNull();
            assertThat(view.heureDebut()).isNull();
            assertThat(view.dureeStationMin()).isNull();
            assertThat(view.tempsBattementMin()).isNull();   // ADR-0012 absent → null
            assertThat(view.stations()).isEmpty();
        }

        @Test
        @DisplayName("ADR-0010 : launched_at bien parsé (format 'yyyy-MM-dd HH:mm:ss')")
        void launchedAt_parse() {
            String body = "{\"success\":true,\"data\":{\"id\":7,\"statut\":\"EN_COURS\"," +
                    "\"launchedAt\":\"2026-06-20 09:37:12\"}}";
            ExamServiceClient client = clientReturning(okJson(body), new ArrayList<>());

            assertThat(client.getExamForGeneration(7L).launchedAt())
                    .isEqualTo(LocalDateTime.of(2026, 6, 20, 9, 37, 12));
        }

        @Test
        @DisplayName("ADR-0010 : launched_at malformé → null, pas d'exception")
        void launchedAt_malformé_null() {
            String body = "{\"success\":true,\"data\":{\"id\":7,\"statut\":\"EN_COURS\"," +
                    "\"launchedAt\":\"not-a-date\"}}";
            ExamServiceClient client = clientReturning(okJson(body), new ArrayList<>());

            ExamGenerationView view = client.getExamForGeneration(7L);

            assertThat(view.launchedAt()).isNull();
            assertThat(view.examenId()).isEqualTo(7L);
        }

        @Test
        @DisplayName("Examen introuvable (data sans id numérique) → BusinessException")
        void examenIntrouvable() {
            ExamServiceClient client = clientReturning(
                    okJson("{\"success\":true,\"data\":{\"message\":\"x\"}}"), new ArrayList<>());

            assertThatThrownBy(() -> client.getExamForGeneration(404L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("introuvable");
        }

        @Test
        @DisplayName("4xx → BusinessException avec le statut HTTP")
        void quatreCent_BusinessException() {
            ClientResponse notFound = ClientResponse.create(HttpStatus.NOT_FOUND)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"success\":false}")
                    .build();
            ExamServiceClient client = clientReturning(notFound, new ArrayList<>());

            assertThatThrownBy(() -> client.getExamForGeneration(7L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("404");
        }

        @Test
        @DisplayName("Erreur réseau → BusinessException 'injoignable'")
        void erreurReseau_BusinessException() {
            ExchangeFunction failing = req -> Mono.error(new RuntimeException("refused"));
            ExamServiceClient client = new ExamServiceClient(
                    WebClient.builder().exchangeFunction(failing).build());

            assertThatThrownBy(() -> client.getExamForGeneration(7L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("injoignable");
        }

        @Test
        @DisplayName("response root null → BusinessException 'introuvable'")
        void responseNull_BusinessException() {
            // data will be null → fail-closed
            ClientResponse nullBody = ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("null")
                    .build();
            ExamServiceClient client = clientReturning(nullBody, new ArrayList<>());

            assertThatThrownBy(() -> client.getExamForGeneration(7L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("introuvable");
        }
    }

    // =========================================================================
    // getExamTiming (ADR-0012 §0)
    // =========================================================================

    @Nested
    @DisplayName("getExamTiming()")
    class GetExamTiming {

        @Test
        @DisplayName("Parse enPause / pausedAt / totalPauseSec / dureeStationMin / avertissementLeadSec + transmet le JWT")
        void happyPath_parseTiming() {
            List<ClientRequest> requests = new ArrayList<>();
            String body = "{\"success\":true,\"data\":{\"id\":16," +
                    "\"enPause\":true,\"pausedAt\":\"2026-06-20 10:15:30\"," +
                    "\"totalPauseSec\":600,\"dureeStationMin\":20," +
                    "\"avertissementLeadSec\":90}}";
            ExamServiceClient client = clientReturning(okJson(body), requests);

            ExamServiceClient.ExamTiming t = client.getExamTiming(16L);

            assertThat(t.enPause()).isTrue();
            assertThat(t.pausedAt()).isEqualTo(LocalDateTime.of(2026, 6, 20, 10, 15, 30));
            assertThat(t.totalPauseSec()).isEqualTo(600);
            assertThat(t.dureeStationMin()).isEqualTo(20);
            assertThat(t.avertissementLeadSec()).isEqualTo(90);   // ADR-0012
            assertThat(requests.get(0).url().toString()).contains("/api/examens/16");
            assertThat(requests.get(0).headers().getFirst("Authorization"))
                    .isEqualTo("Bearer fake-test-token");
        }

        @Test
        @DisplayName("Examen non en pause → enPause=false, pausedAt=null, defaults sûrs")
        void pasEnPause_defaults() {
            String body = "{\"success\":true,\"data\":{\"id\":1,\"enPause\":false}}";
            ExamServiceClient client = clientReturning(okJson(body), new ArrayList<>());

            ExamServiceClient.ExamTiming t = client.getExamTiming(1L);

            assertThat(t.enPause()).isFalse();
            assertThat(t.pausedAt()).isNull();
            assertThat(t.totalPauseSec()).isZero();
            assertThat(t.dureeStationMin()).isNull();
            assertThat(t.avertissementLeadSec()).isZero();   // ADR-0012 absent → 0
        }

        @Test
        @DisplayName("pausedAt malformé → null, le reste du timing reste lu")
        void pausedAtMalformé_null() {
            String body = "{\"success\":true,\"data\":{\"id\":1,\"enPause\":true," +
                    "\"pausedAt\":\"pas-une-date\",\"totalPauseSec\":120}}";
            ExamServiceClient client = clientReturning(okJson(body), new ArrayList<>());

            ExamServiceClient.ExamTiming t = client.getExamTiming(1L);

            assertThat(t.pausedAt()).isNull();
            assertThat(t.enPause()).isTrue();
            assertThat(t.totalPauseSec()).isEqualTo(120);
        }

        @Test
        @DisplayName("Examen introuvable (data sans id) → état neutre, pas d'exception")
        void examenIntrouvable_neutre() {
            ExamServiceClient client = clientReturning(
                    okJson("{\"success\":true,\"data\":{\"message\":\"x\"}}"), new ArrayList<>());

            ExamServiceClient.ExamTiming t = client.getExamTiming(404L);

            assertThat(t.enPause()).isFalse();
            assertThat(t.totalPauseSec()).isZero();
            assertThat(t.dureeStationMin()).isNull();
        }

        @Test
        @DisplayName("Erreur réseau → état neutre (fail-soft), pas d'exception")
        void erreurReseau_neutre() {
            ExchangeFunction failing = req -> Mono.error(new RuntimeException("timeout"));
            ExamServiceClient client = new ExamServiceClient(
                    WebClient.builder().exchangeFunction(failing).build());

            ExamServiceClient.ExamTiming t = client.getExamTiming(5L);

            assertThat(t.enPause()).isFalse();
            assertThat(t.pausedAt()).isNull();
            assertThat(t.totalPauseSec()).isZero();
        }
    }

    // =========================================================================
    // getMatiereIdStrict (#274) — la source de la matière figée
    //
    // STRICT au sens d'ADR-0015 : en cas d'échec on ne devine RIEN. Une matière devinée serait
    // figée définitivement et autoriserait durablement le mauvais responsable — dégât permanent,
    // là où un refus est réversible.
    // =========================================================================

    @Nested
    @DisplayName("getMatiereIdStrict (#274)")
    class GetMatiereIdStrict {

        @Test
        @DisplayName("Lit data.matiereId et appelle /api/examens/{id}")
        void happyPath() {
            List<ClientRequest> captured = new ArrayList<>();
            ExamServiceClient client = clientReturning(
                    okJson("{\"success\":true,\"data\":{\"id\":42,\"matiereId\":7,\"nom\":\"x\"}}"),
                    captured);

            assertThat(client.getMatiereIdStrict(42L)).isEqualTo(7L);
            assertThat(captured).hasSize(1);
            assertThat(captured.get(0).url().getPath()).isEqualTo("/api/examens/42");
        }

        /**
         * LE piège que {@code asText(null)} évite. Avec {@code asLong()}, un nœud MISSING vaut
         * <b>0</b> : la matière 0 aurait été figée en silence, et plus aucun responsable n'aurait
         * jamais pu écrire sur cet examen — ou pire, un jeton portant « :0 » aurait pu.
         */
        @Test
        @DisplayName("matiereId ABSENT → échoue, ne fige jamais 0")
        void matiereAbsente_echoue() {
            assertThatThrownBy(() -> clientReturning(
                    okJson("{\"success\":true,\"data\":{\"id\":42,\"nom\":\"x\"}}"),
                    new ArrayList<>()).getMatiereIdStrict(42L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("n'a pas fourni de matière");
        }

        @Test
        @DisplayName("matiereId illisible → échoue en le citant")
        void matiereIllisible_echoue() {
            assertThatThrownBy(() -> clientReturning(
                    okJson("{\"success\":true,\"data\":{\"matiereId\":\"abc\"}}"),
                    new ArrayList<>()).getMatiereIdStrict(42L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("illisible");
        }

        /**
         * Le 403 d'exam-service quand un responsable étranger demande l'examen : il doit devenir
         * un refus franc côté scoring, jamais une matière par défaut.
         */
        @Test
        @DisplayName("403 d'exam-service → échoue en citant le code")
        void refusAmont_echoue() {
            ClientResponse forbidden = ClientResponse.create(HttpStatus.FORBIDDEN)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"success\":false,\"message\":\"Accès refusé\"}")
                    .build();

            assertThatThrownBy(() -> clientReturning(forbidden, new ArrayList<>())
                    .getMatiereIdStrict(42L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("403");
        }

        @Test
        @DisplayName("exam-service injoignable → échoue, aucune valeur inventée")
        void injoignable_echoue() {
            ExchangeFunction failing = req -> Mono.error(new RuntimeException("connexion refusée"));
            ExamServiceClient client = new ExamServiceClient(
                    WebClient.builder().exchangeFunction(failing).build());

            assertThatThrownBy(() -> client.getMatiereIdStrict(42L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("injoignable");
        }

        /**
         * Les variantes STRICTES ne consultent jamais l'état de santé : elles tentent toujours
         * l'appel. Un faux positif bloquerait sinon toute autorisation pendant la fenêtre de
         * repli, sur le chemin précisément protégé par ADR-0015.
         */
        @Test
        @DisplayName("Ne consulte PAS la fenêtre de repli : tente l'appel même après une panne")
        void neConsultePasLaSanté() {
            AtomicInteger appels = new AtomicInteger();
            ExchangeFunction exchange = req -> {
                if (appels.incrementAndGet() == 1) {
                    return Mono.error(new RuntimeException("panne"));
                }
                return Mono.just(okJson("{\"success\":true,\"data\":{\"matiereId\":7}}"));
            };
            ExamServiceClient client = new ExamServiceClient(
                    WebClient.builder().exchangeFunction(exchange).build());

            assertThatThrownBy(() -> client.getMatiereIdStrict(42L))
                    .isInstanceOf(BusinessException.class);
            // Immédiatement après la panne : le second appel PART quand même.
            assertThat(client.getMatiereIdStrict(42L)).isEqualTo(7L);
            assertThat(appels.get()).isEqualTo(2);
        }
    }
}