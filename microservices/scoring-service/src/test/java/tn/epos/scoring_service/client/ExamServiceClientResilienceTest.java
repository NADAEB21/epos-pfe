package tn.epos.scoring_service.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import io.netty.channel.ChannelOption;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Résilience du client exam-service — régression du 2026-07-20.
 *
 * <p><b>Le défaut.</b> {@code WebClient.builder().baseUrl(...).build()} n'avait AUCUN délai
 * configuré : chaque appel héritait du défaut Netty/OS (~3 s mesuré). Le dashboard en enchaîne
 * une dizaine, d'où 31–61 s pendant une panne d'exam-service — alors que le client mobile
 * abandonne à 20 s. L'évaluateur ne voyait donc jamais le tableau dégradé, seulement
 * « Impossible de charger les sessions : Vérifiez votre connexion réseau », qui accuse SON
 * réseau d'une panne serveur.
 *
 * <p><b>Pourquoi borner les délais ne suffit pas</b> — et pourquoi ce test vérifie les DEUX
 * moitiés : 11 appels × 2 s = 22 s, toujours au-delà du budget client. Il faut aussi cesser de
 * réessayer une fois la panne constatée.
 */
@DisplayName("ExamServiceClient — résilience (délais bornés + repli immédiat)")
class ExamServiceClientResilienceTest {

    /** Port fermé : la connexion est refusée immédiatement en local, ou expire. */
    private static final String PORT_MORT = "http://127.0.0.1:59999";

    // Chaque test construit SON client : l'état de santé ne doit pas fuir d'un test
    // à l'autre, sinon une panne provoquée ici court-circuiterait un test voisin.
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

    private ExamServiceClient clientVersPortMort(Duration fenetreRepli) {
        WebClient wc = WebClient.builder()
                .baseUrl(PORT_MORT)
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create()
                                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                                        (int) ExamServiceClient.CONNECT_TIMEOUT.toMillis())
                                .responseTimeout(ExamServiceClient.RESPONSE_TIMEOUT)))
                .build();
        return new ExamServiceClient(wc, fenetreRepli);
    }

    @Nested
    @DisplayName("Repli immédiat")
    class RepliImmediat {

        @Test
        @DisplayName("le 2e appel d'affichage court-circuite : il ne repaie PAS le délai réseau")
        void deuxiemeAppelCourtCircuite() {
            ExamServiceClient client = clientVersPortMort(Duration.ofSeconds(5));

            // 1er appel : constate la panne (paie le délai, borné).
            client.getExamTiming(1L);
            assertThat(client.estProbablementInjoignable())
                    .as("une panne de connectivité doit ouvrir la fenêtre de repli")
                    .isTrue();

            // 2e appel : doit revenir quasi instantanément, sans toucher le réseau.
            long t0 = System.nanoTime();
            ExamServiceClient.ExamTiming timing = client.getExamTiming(2L);
            long ecouleMs = (System.nanoTime() - t0) / 1_000_000;

            assertThat(ecouleMs)
                    .as("2e appel en %d ms — il doit court-circuiter, pas réessayer", ecouleMs)
                    .isLessThan(100);
            // Le repli reste l'état neutre : le court-circuit change la LATENCE, pas la sémantique.
            assertThat(timing.enPause()).isFalse();
            assertThat(timing.statut()).isNull();
        }

        @Test
        @DisplayName("la fenêtre EXPIRE — on retente, sinon une panne brève deviendrait permanente")
        void fenetreExpire() throws InterruptedException {
            ExamServiceClient client = clientVersPortMort(Duration.ofMillis(150));
            client.getExamTiming(1L);
            assertThat(client.estProbablementInjoignable()).isTrue();

            Thread.sleep(250);

            assertThat(client.estProbablementInjoignable())
                    .as("passé la fenêtre, le client doit re-sonder exam-service")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Délais bornés")
    class DelaisBornes {

        @Test
        @DisplayName("un appel STRICT échoue vite et n'est JAMAIS court-circuité")
        void appelStrictTenteToujours() {
            ExamServiceClient client = clientVersPortMort(Duration.ofSeconds(5));

            // On ouvre d'abord la fenêtre via un appel d'affichage.
            client.getExamTiming(1L);
            assertThat(client.estProbablementInjoignable()).isTrue();

            // Le chemin STRICT (notation) doit quand même TENTER : un court-circuit ici
            // transformerait un faux positif en blocage de la notation pendant toute la
            // fenêtre — exactement le chemin qu'ADR-0015 protège. Il échoue, mais borné.
            long t0 = System.nanoTime();
            assertThatStrictThrows(client);
            long ecouleMs = (System.nanoTime() - t0) / 1_000_000;

            assertThat(ecouleMs)
                    .as("l'appel strict a pris %d ms — il doit rester borné par CONNECT_TIMEOUT", ecouleMs)
                    .isLessThan(ExamServiceClient.CONNECT_TIMEOUT.toMillis() + 1500);
        }

        private void assertThatStrictThrows(ExamServiceClient client) {
            try {
                client.getStationNomStrict(5L);
                org.junit.jupiter.api.Assertions.fail(
                        "getStationNomStrict doit échouer fort quand exam-service est injoignable");
            } catch (RuntimeException expected) {
                assertThat(expected.getMessage()).contains("injoignable");
            }
        }
    }

    @Nested
    @DisplayName("Classement des échecs")
    class ClassementEchecs {

        @Test
        @DisplayName("une réponse HTTP d'erreur n'ouvre PAS la fenêtre — le service a répondu")
        void reponseHttpNOuvrePasLaFenetre() {
            // 404 sur UNE station ne doit pas nous rendre aveugles à toutes les autres :
            // exam-service est debout, il a répondu. Seule une panne de CONNECTIVITÉ compte.
            WebClient wc = WebClient.builder()
                    .baseUrl("http://127.0.0.1:59999")
                    .exchangeFunction(req -> reactor.core.publisher.Mono.error(
                            org.springframework.web.reactive.function.client.WebClientResponseException
                                    .create(404, "Not Found",
                                            org.springframework.http.HttpHeaders.EMPTY,
                                            new byte[0], null)))
                    .build();
            ExamServiceClient client = new ExamServiceClient(wc, Duration.ofSeconds(5));

            client.getExamTiming(1L);

            assertThat(client.estProbablementInjoignable())
                    .as("un 404 prouve qu'exam-service RÉPOND — la fenêtre doit rester fermée")
                    .isFalse();
        }
    }
}
