package tn.epos.scoring_service.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tn.epos.common.exception.BusinessException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.*;

/**
 * Client cross-service vers exam-service.
 *
 * Fournit les informations de pondération des items d'une grille,
 * nécessaires pour calculer le score final côté scoring-service
 * avec la même formule que le client Flutter (ScoreUtils.calculerScore).
 */
@Component
public class ExamServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ExamServiceClient.class);
    private static final int ITEMS_PAGE_SIZE = 500;

    // ExamenResponse sérialise launched_at en "yyyy-MM-dd HH:mm:ss" (espace, pas
    // le 'T' ISO) via @JsonFormat — il faut le même motif pour le relire.
    private static final DateTimeFormatter LAUNCHED_AT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** SonarQube S1192 — littéral réutilisé pour le nom de repli d'une station inconnue. */
    private static final String STATION_FALLBACK_PREFIX = "Station ";

    /** SonarQube S1192 — nom du champ JSON lu dans extractExamView + getExamTiming. */
    private static final String FIELD_DUREE_STATION_MIN = "dureeStationMin";

    /** SonarQube S1192 — champ JSON du tampon inter-créneau (ADR-0012). */
    private static final String FIELD_TEMPS_BATTEMENT_MIN = "tempsBattementMin";

    /** SonarQube S1192 — champ JSON du délai d'avertissement (ADR-0012). */
    private static final String FIELD_AVERTISSEMENT_LEAD_SEC = "avertissementLeadSec";

    /**
     * Informations d'un item d'évaluation issues de l'exam-service.
     *
     * @param id          identifiant de l'item dans exam_db
     * @param ponderation poids en points (ex : 2 pour "Choix indicateur", 6 pour "Calcul masse")
     * @param type        "BINAIRE" ou "NUMERIQUE"
     */
    public record ItemInfo(Long id, double ponderation, String type) {}

    /**
     * Informations sommaires d'une station (nom, etc.)
     * utilisées par EvaluateurDashboardService pour enrichir les SessionResponse.
     */
    public record StationInfo(String nom) {}

    /**
     * Snapshot de l'état temporel d'un examen, lu par
     * {@code EvaluateurDashboardService} pour calculer le <b>temps effectif</b>
     * (ADR-0009/0010, ADR-0012 §0) : l'horloge live de l'évaluateur doit
     * soustraire le temps de pause, sinon une session affichée pendant une pause
     * compte vers le mauvais étudiant.
     *
     * @param enPause       l'examen est-il actuellement en pause
     * @param pausedAt      début de la pause en cours (null si non en pause) —
     *                      moment serveur estampillé, zone {@code app.timezone}
     * @param totalPauseSec secondes de pause cumulées sur les intervalles terminés
     * @param dureeStationMin durée nominale d'une station (config examen) —
     *                      remplace la constante codée en dur côté dashboard
     * @param avertissementLeadSec délai (secondes) avant le prochain passage
     *                      auquel l'app évaluateur déclenche l'avertissement
     *                      (ADR-0012) ; 0 = avertissements désactivés
     */
    public record ExamTiming(boolean enPause, LocalDateTime pausedAt,
                             int totalPauseSec, Integer dureeStationMin,
                             int avertissementLeadSec, String statut) {

        /** État neutre (pas de pause, pas d'avertissement) — repli si exam-service est injoignable. */
        public static ExamTiming neutral() {
            return new ExamTiming(false, null, 0, null, 0, null);
        }
    }

    /**
     * Plafond d'établissement de connexion. Sans ceci, WebClient héritait du défaut
     * Netty/OS : mesuré le 2026-07-20, ~3 s par appel, et le dashboard en enchaîne 11.
     */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /** Plafond de réponse — exam-service joignable mais bloqué ne doit pas bloquer la notation. */
    static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(3);

    /**
     * Fenêtre de « repli immédiat » après une panne de connectivité constatée.
     *
     * <p>Court à dessein : la récupération d'exam-service est rapide (≈10 s, vérifié session 20),
     * donc la fenêtre borne la fraîcheur perdue une fois le service revenu.
     */
    static final Duration FENETRE_REPLI = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final long fenetreRepliNanos;

    /**
     * Échéance jusqu'à laquelle exam-service est réputé injoignable.
     *
     * <p><b>Ce n'est pas un cache, c'est un état de santé.</b> Comparaison par SOUSTRACTION
     * ({@code now - echeance < 0}) : c'est le seul idiome correct avec {@link System#nanoTime()},
     * dont l'origine est arbitraire et qui peut être négatif.
     */
    private final AtomicLong injoignableJusquA = new AtomicLong();

    // Cache permanent : les pondérations ne changent pas une fois l'examen EN_COURS.
    private final ConcurrentHashMap<Long, Map<Long, ItemInfo>> grilleItemsCache =
            new ConcurrentHashMap<>();

    @Autowired
    public ExamServiceClient(
            @Value("${exam-service.base-url:http://localhost:8082}") String baseUrl) {
        this(WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create()
                                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                                        (int) CONNECT_TIMEOUT.toMillis())
                                .responseTimeout(RESPONSE_TIMEOUT)))
                .build());
    }

    /** Package-private pour les tests — inject un WebClient stubbé. */
    ExamServiceClient(WebClient webClient) {
        this(webClient, FENETRE_REPLI);
    }

    /** Package-private pour les tests — fenêtre de repli réglable. */
    ExamServiceClient(WebClient webClient, Duration fenetreRepli) {
        this.webClient = webClient;
        this.fenetreRepliNanos = fenetreRepli.toNanos();
        this.injoignableJusquA.set(System.nanoTime()); // échéance déjà passée ⇒ sain
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Santé d'exam-service — pourquoi le repli est CONSULTÉ et non IMPOSÉ
    //
    // Mesuré le 2026-07-20 : dashboard 0,28 s à l'état sain, 31–61 s pendant une
    // panne, alors que le client mobile abandonne à 20 s. L'évaluateur ne voyait
    // donc jamais le tableau dégradé — juste « Impossible de charger les sessions :
    // Vérifiez votre connexion réseau », qui accuse SON réseau d'une panne serveur.
    // Borner les délais ne suffit pas : 11 appels × 2 s = 22 s, toujours trop.
    //
    // ⚠️ Les appelants STRICTS ne consultent JAMAIS cet état ; ils tentent toujours
    // l'appel (borné à 2 s). Un faux positif — exam-service debout, un appel expiré
    // sous charge — bloquerait sinon la NOTATION pendant toute la fenêtre, sur le
    // chemin précisément protégé par ADR-0015. Le gain y serait nul de toute façon :
    // snapshot chaud = 0 appel (0,056 s mesuré), snapshot froid = 1 seul appel.
    // Seuls les appelants d'AFFICHAGE consultent — eux en font N.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * exam-service est-il réputé injoignable, d'après la dernière panne de connectivité ?
     *
     * <p>À consulter <b>uniquement</b> par les chemins d'affichage, qui ont un repli honnête à
     * servir. Un chemin d'écriture doit tenter l'appel : lui, n'a pas de repli acceptable.
     */
    public boolean estProbablementInjoignable() {
        return System.nanoTime() - injoignableJusquA.get() < 0;
    }

    /**
     * Classe un échec. Seule une panne de <b>connectivité</b> ouvre la fenêtre de repli :
     * une {@link WebClientResponseException} prouve qu'exam-service a RÉPONDU — un 404 sur une
     * station ne doit pas nous rendre aveugles à toutes les autres.
     */
    private void classerEchec(Throwable e) {
        if (e instanceof WebClientResponseException) return;
        long echeance = System.nanoTime() + fenetreRepliNanos;
        if (!estProbablementInjoignable()) {
            log.warn("exam-service injoignable — repli immédiat pendant {} s (au lieu d'attendre "
                    + "l'expiration de chaque appel)", FENETRE_REPLI.toSeconds());
        }
        injoignableJusquA.set(echeance);
    }

    /** Un appel a abouti : exam-service est debout, on referme immédiatement. */
    private void signalerSucces() {
        if (estProbablementInjoignable()) {
            log.info("exam-service de nouveau joignable — fin du repli immédiat");
        }
        injoignableJusquA.set(System.nanoTime());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API publique
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retourne la map itemId → ItemInfo pour la grille, mise en cache si succès.
     * En cas d'indisponibilité de l'exam-service : retourne une map vide (non cachée)
     * pour que recalculerScoreFinal puisse utiliser un fallback sans bloquer.
     */
    public Map<Long, ItemInfo> getItemInfosForGrille(Long grilleId) {
        Map<Long, ItemInfo> cached = grilleItemsCache.get(grilleId);
        if (cached != null) return cached;

        Map<Long, ItemInfo> infos = fetchItemInfos(grilleId);
        if (!infos.isEmpty()) {
            grilleItemsCache.put(grilleId, infos);
        }
        return infos;
    }

    /** Compatibilité avec NotationItemService. */
    public Set<Long> getItemIdsForGrille(Long grilleId) {
        return getItemInfosForGrille(grilleId).keySet();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADR-0015 — variantes STRICTES, utilisées uniquement par
    // ExamDefinitionSnapshotService pour figer la définition.
    //
    // Les variantes historiques ci-dessus dégradent en silence (map vide /
    // « Station <id> ») pour ne pas bloquer l'appelant. C'est précisément ce qui
    // faisait enregistrer des notes fausses pendant une panne d'exam-service.
    // Une matérialisation ne doit JAMAIS figer une valeur de repli : ce qu'on
    // écrit ici est définitif. En cas d'échec : ne rien écrire, échouer fort.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Nom réel de la station, sans repli.
     *
     * @throws BusinessException si exam-service est injoignable, répond en erreur, ou ne fournit
     *                           pas de nom. Ne renvoie jamais {@code "Station <id>"}.
     */
    public String getStationNomStrict(Long stationId) {
        String bearerToken = currentBearerToken();
        JsonNode root;
        try {
            root = webClient.get()
                    .uri("/api/stations/{id}", stationId)
                    .headers(h -> h.setBearerAuth(bearerToken))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientResponseException e) {
            throw new BusinessException("exam-service a renvoyé " + e.getStatusCode().value()
                    + " pour la station " + stationId + " — définition non figée (ADR-0015).");
        } catch (RuntimeException e) {
            throw new BusinessException("exam-service injoignable pour la station " + stationId
                    + " — définition non figée (ADR-0015) : " + e.getMessage());
        }

        String nom = (root != null) ? root.path("data").path("nom").asText(null) : null;
        if (nom == null || nom.isBlank()) {
            throw new BusinessException("exam-service n'a pas fourni de nom pour la station "
                    + stationId + " — définition non figée (ADR-0015).");
        }
        return nom;
    }

    /**
     * Items notables (feuilles) d'une grille, sans repli sur une map vide.
     *
     * <p>{@link #fetchItemInfos} lève déjà sur erreur de transport ; la dégradation silencieuse
     * venait d'un corps nul renvoyant une map vide. On la rejette ici.
     *
     * @throws BusinessException si la grille ne peut pas être lue ou ne déclare aucun item.
     */
    public Map<Long, ItemInfo> getItemInfosForGrilleStrict(Long grilleId) {
        Map<Long, ItemInfo> infos = fetchItemInfos(grilleId);
        if (infos.isEmpty()) {
            throw new BusinessException("exam-service n'a renvoyé aucun critère notable pour la "
                    + "grille " + grilleId + " — définition non figée (ADR-0015).");
        }
        return infos;
    }

    /**
     * Matière propriétaire d'un examen, sans repli — #274.
     *
     * <p>Sert à figer {@code exam_matiere_snapshot} une seule fois par examen, pour que
     * l'autorisation par matière du jour J soit ensuite purement locale (ADR-0015).
     *
     * <p><b>Le cas du tout premier appel.</b> {@code GET /api/examens/{id}} est déjà réservé à
     * {@code SUPER_ADMIN | RESPONSABLE_MATIERE} <b>et</b> déjà borné par matière côté
     * exam-service ({@code ExamenServiceImpl.trouverParId} → {@code checkAccess}). Un
     * responsable étranger reçoit donc un 403 d'exam-service <b>avant même</b> que la ligne
     * locale existe : la garde de #274 est effective dès le premier appel, sans état préalable.
     *
     * <p>Ce 403 amont est traduit en {@link AccessDeniedException}, donc en <b>403</b> pour le
     * client, et non en erreur de transport. Mesuré en direct le 2026-08-10 avant ce
     * traitement : le responsable de Toxicologie était bien refusé sur l'examen 53, mais en
     * <b>400</b> avec le message « matière non figée » — un code faux, et une explication qui
     * parle de plomberie interne. Le frontend distingue 403 et 400 ; c'est le code qui porte
     * le sens ici.
     *
     * <p>⚠️ Le message détaillé ci-dessous n'atteint PAS l'utilisateur : le
     * {@code GlobalExceptionHandler} aplatit tout 403 en « Access denied » (choix délibéré et
     * antérieur, identique côté exam-service). Il sert donc les journaux et le diagnostic. Rendre
     * les refus de périmètre explicites à l'écran est un sujet à part — il touche TOUS les 403 du
     * service — et n'est pas traité ici.
     *
     * <p>⚠️ Ce n'est PAS une garde qui dépend du réseau : dès la ligne figée, la décision est
     * locale et ce chemin n'est plus emprunté (vérifié en direct — 403 local après figeage).
     * C'est l'amorçage, et il a lieu en préparation, exam-service debout.
     *
     * @throws AccessDeniedException si exam-service refuse l'examen à cet appelant (403/401) —
     *                               c'est une réponse d'autorisation, pas une panne
     * @throws BusinessException si exam-service est injoignable, répond une autre erreur, ou ne
     *                           fournit pas de matière. Ne devine JAMAIS une matière : figer une
     *                           matière fausse autoriserait durablement le mauvais responsable.
     */
    public Long getMatiereIdStrict(Long examenId) {
        String bearerToken = currentBearerToken();
        JsonNode root;
        try {
            root = webClient.get()
                    .uri("/api/examens/{id}", examenId)
                    .headers(h -> h.setBearerAuth(bearerToken))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientResponseException e) {
            int code = e.getStatusCode().value();
            if (code == 403 || code == 401) {
                // exam-service est PROPRIÉTAIRE de la matière : son refus est la réponse
                // d'autorisation, pas un incident. On la relaie sans la déguiser.
                throw new AccessDeniedException(
                        "Accès interdit : l'examen " + examenId
                                + " ne relève pas de vos matières (#274).");
            }
            throw new BusinessException("exam-service a renvoyé " + code
                    + " pour l'examen " + examenId
                    + " — matière non figée, écriture refusée (#274, ADR-0015).");
        } catch (RuntimeException e) {
            throw new BusinessException("exam-service injoignable pour l'examen " + examenId
                    + " — matière non figée, écriture refusée (#274, ADR-0015) : " + e.getMessage());
        }

        // asText(null) puis parse explicite : un `matiereId` absent rend un noeud MISSING, dont
        // asLong() vaut 0 — un identifiant plausible qu'on figerait en silence.
        String brut = (root != null) ? root.path("data").path("matiereId").asText(null) : null;
        if (brut == null || brut.isBlank()) {
            throw new BusinessException("exam-service n'a pas fourni de matière pour l'examen "
                    + examenId + " — matière non figée, écriture refusée (#274, ADR-0015).");
        }
        try {
            return Long.parseLong(brut.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("exam-service a renvoyé une matière illisible « " + brut
                    + " » pour l'examen " + examenId + " — matière non figée (#274, ADR-0015).");
        }
    }

    /**
     * Grille complète d'une station, sans repli. Suit le patron le plus COMPLET
     * du client (fetchItemInfos), pas celui — incomplet — de
     * getStationNomStrict : sans classerEchec/signalerSucces, un échec ici
     * n'ouvrirait pas la fenêtre de repli immédiat pour les autres appels
     * d'AFFICHAGE pendant la même panne.
     */
    public JsonNode getGrilleStrict(Long stationId) {
        String bearerToken = currentBearerToken();
        try {
            JsonNode root = webClient.get()
                    .uri("/api/stations/{id}/grille", stationId)
                    .headers(h -> h.setBearerAuth(bearerToken))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            signalerSucces();

            JsonNode data = root == null ? null : root.path("data");
            if (data == null || data.isMissingNode() || data.isNull() || !data.path("id").isNumber()) {
                throw new BusinessException("exam-service n'a pas fourni de grille pour la station "
                        + stationId + " — définition non figée.");
            }
            return data;
        } catch (WebClientResponseException e) {
            classerEchec(e);
            throw new BusinessException("exam-service a renvoyé " + e.getStatusCode().value()
                    + " pour la grille de la station " + stationId + " — définition non figée.");
        } catch (RuntimeException e) {
            classerEchec(e);
            throw new BusinessException("exam-service injoignable pour la grille de la station "
                    + stationId + " — définition non figée : " + e.getMessage());
        }
    }

    /**
     * Récupère le nom d'une station depuis l'exam-service.
     * Résultat non mis en cache (champ mutable si examen en brouillon).
     */
    public StationInfo getStationInfo(Long stationId) {
        String bearerToken = currentBearerToken();
        try {
            JsonNode root = webClient.get()
                    .uri("/api/stations/{id}", stationId)
                    .headers(h -> h.setBearerAuth(bearerToken))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            String nom = (root != null)
                    ? root.path("data").path("nom").asText(STATION_FALLBACK_PREFIX + stationId)
                    : STATION_FALLBACK_PREFIX + stationId;
            signalerSucces();
            return new StationInfo(nom);
        } catch (Exception e) {
            classerEchec(e);
            log.warn("exam-service injoignable pour station {} : {}", stationId, e.getMessage());
            return new StationInfo(STATION_FALLBACK_PREFIX + stationId);
        }
    }

    /**
     * Lit l'état temporel (pause + durée station) d'un examen pour le calcul du
     * temps effectif côté dashboard (ADR-0012 §0). <b>Fail-soft</b> : en cas
     * d'indisponibilité de l'exam-service ou d'examen introuvable, retourne
     * {@link ExamTiming#neutral()} (pas de pause) plutôt que d'échouer — le
     * dashboard dégrade alors vers l'horloge murale brute, comme
     * {@link #getStationInfo(Long)}. Non mis en cache : la pause est mutable.
     */
    public ExamTiming getExamTiming(Long examenId) {
        // Appelant d'AFFICHAGE : il a un repli honnête (état neutre), donc il consulte.
        // Le dashboard en fait un par examen — et #241 (repli-ouvert du filtre de statut)
        // fait passer eval3 de 1 à 3 examens, donc 1 à 3 attentes. Sans ce court-circuit,
        // chaque appel paie son délai plein.
        if (estProbablementInjoignable()) {
            return ExamTiming.neutral();
        }
        String bearerToken = currentBearerToken();
        try {
            // /timing — vue minimale (statut / pause / durée) LISIBLE PAR L'ÉVALUATEUR.
            //
            // On appelait /api/examens/{id}, réservé à SUPER_ADMIN | RESPONSABLE_MATIERE :
            // avec le jeton d'un évaluateur, c'était un 403 systématique, avalé plus bas dans
            // ExamTiming.neutral() (statut = null). Le filtre "examens EN_COURS" du dashboard
            // ne trouvait alors AUCUN examen et l'évaluateur se retrouvait avec un dashboard
            // VIDE le jour de l'examen. Ne pas rebasculer cet appel sur /api/examens/{id}.
            JsonNode root = webClient.get()
                    .uri("/api/examens/{id}/timing", examenId)
                    .headers(h -> h.setBearerAuth(bearerToken))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            JsonNode data = root == null ? null : root.path("data");
            if (data == null || data.isMissingNode() || data.isNull() || !data.path("id").isNumber()) {
                log.warn("getExamTiming : examen {} introuvable — état neutre", examenId);
                return ExamTiming.neutral();
            }
            boolean enPause = data.path("enPause").asBoolean(false);
            LocalDateTime pausedAt = parseServerTimestamp(data.path("pausedAt"));
            int totalPauseSec = data.path("totalPauseSec").isNumber()
                    ? data.path("totalPauseSec").asInt() : 0;
            Integer duree = data.path(FIELD_DUREE_STATION_MIN).isNumber()
                    ? data.path(FIELD_DUREE_STATION_MIN).asInt() : null;
            int leadSec = data.path(FIELD_AVERTISSEMENT_LEAD_SEC).isNumber()
                    ? data.path(FIELD_AVERTISSEMENT_LEAD_SEC).asInt() : 0;
            String statut = data.path("statut").isTextual() ? data.path("statut").asText() : null;
            signalerSucces();
            return new ExamTiming(enPause, pausedAt, totalPauseSec, duree, leadSec, statut);
        } catch (Exception e) {
            classerEchec(e);
            log.warn("exam-service injoignable pour timing examen {} : {} — état neutre",
                    examenId, e.getMessage());
            return ExamTiming.neutral();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Implémentation
    // ─────────────────────────────────────────────────────────────────────────

    private Map<Long, ItemInfo> fetchItemInfos(Long grilleId) {
        String bearerToken = currentBearerToken();
        try {
            JsonNode root = webClient.get()
                    .uri("/api/grilles/{grilleId}/items/feuilles", grilleId)
                    .headers(h -> h.setBearerAuth(bearerToken))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            signalerSucces();
            return extractItemInfos(root, grilleId);
        } catch (WebClientResponseException e) {
            classerEchec(e);   // réponse HTTP ⇒ service debout ⇒ n'ouvre PAS la fenêtre
            log.error("exam-service HTTP {} pour grille {}", e.getStatusCode(), grilleId);
            throw new BusinessException("exam-service a renvoyé " + e.getStatusCode().value());
        } catch (RuntimeException e) {
            classerEchec(e);
            log.error("exam-service injoignable pour grille {}", grilleId, e);
            throw new BusinessException("exam-service injoignable : " + e.getMessage());
        }
    }

    /**
     * Fetches the exam + its stations for rotation generation. NOT cached —
     * the roster and station set change during authoring, so generation must
     * read the live state every time. Forwards the caller's JWT.
     *
     * @throws BusinessException if exam-service is unreachable, errors, or the
     *                           exam does not exist (fail-closed)
     */
    public ExamGenerationView getExamForGeneration(Long examenId) {
        String bearerToken = currentBearerToken();
        JsonNode root;
        try {
            root = webClient.get()
                    .uri("/api/examens/{id}", examenId)
                    .headers(h -> h.setBearerAuth(bearerToken))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientResponseException e) {
            classerEchec(e);
            log.error("exam-service rejected exam lookup for {} (status {}): {}",
                    examenId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(
                    "Génération impossible : exam-service a renvoyé " + e.getStatusCode());
        } catch (RuntimeException e) {
            classerEchec(e);
            log.error("exam-service unreachable for exam {} lookup", examenId, e);
            throw new BusinessException(
                    "Génération impossible : exam-service injoignable");
        }

        JsonNode data = root == null ? null : root.path("data");
        if (data == null || data.isMissingNode() || data.isNull() || !data.path("id").isNumber()) {
            throw new BusinessException("Génération impossible : examen " + examenId + " introuvable");
        }
        return extractExamView(data);
    }

    private ExamGenerationView extractExamView(JsonNode data) {
        List<ExamGenerationView.StationView> stations = new ArrayList<>();
        JsonNode stationsNode = data.path("stations");
        if (stationsNode.isArray()) {
            for (JsonNode s : stationsNode) {
                long id = s.path("id").asLong(-1);
                if (id <= 0) continue;
                Integer ordre = s.path("ordre").isNumber() ? s.path("ordre").asInt() : null;
                List<Long> evaluateurIds = new ArrayList<>();
                for (JsonNode ev : s.path("evaluateurIds")) {
                    long evId = ev.asLong(-1);
                    if (evId > 0) evaluateurIds.add(evId);
                }
                stations.add(new ExamGenerationView.StationView(id, ordre, evaluateurIds));
            }
        }

        return new ExamGenerationView(
                data.path("id").asLong(),
                data.path("nom").asText(null),
                data.path("dateExamen").isTextual() ? LocalDate.parse(data.path("dateExamen").asText()) : null,
                data.path("heureDebut").isTextual() ? LocalTime.parse(data.path("heureDebut").asText()) : null,
                parseLaunchedAt(data.path("launchedAt")),
                data.path(FIELD_DUREE_STATION_MIN).isNumber() ? data.path(FIELD_DUREE_STATION_MIN).asInt() : null,
                data.path(FIELD_TEMPS_BATTEMENT_MIN).isNumber() ? data.path(FIELD_TEMPS_BATTEMENT_MIN).asInt() : null,
                data.path("nbEtudiantsParStation").isNumber() ? data.path("nbEtudiantsParStation").asInt() : null,
                data.path("statut").asText(null),
                stations);
    }

    /**
     * Reads the optional {@code launched_at} (ADR-0010). Absent / null / legacy
     * rows yield {@code null} → generation falls back to the planned start. A
     * malformed value is logged and treated as absent rather than failing the
     * whole generation.
     */
    private LocalDateTime parseLaunchedAt(JsonNode node) {
        return parseServerTimestamp(node);
    }

    /**
     * Reads a machine-stamped {@code "yyyy-MM-dd HH:mm:ss"} timestamp
     * (launched_at, paused_at — same {@code @JsonFormat} on ExamenResponse).
     * Absent / null / malformed → {@code null}, logged but never fatal.
     */
    private LocalDateTime parseServerTimestamp(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return LocalDateTime.parse(node.asText(), LAUNCHED_AT_FMT);
        } catch (DateTimeParseException e) {
            log.warn("timestamp serveur illisible ('{}') — ignoré", node.asText());
            return null;
        }
    }

    private Map<Long, ItemInfo> extractItemInfos(JsonNode root, Long grilleId) {
        Map<Long, ItemInfo> infos = new HashMap<>();
        if (root == null) {
            log.warn("Réponse null pour grille {}", grilleId);
            return infos;
        }
        // /items/feuilles renvoie ApiResponse<List<ItemResponse>> (pas de pagination)
        JsonNode content = root.path("data");
        if (!content.isArray()) {
            log.warn("Réponse inattendue de l'exam-service pour grille {} : data[] absent", grilleId);
            return infos;
        }
        for (JsonNode item : content) {
            long id = item.path("id").asLong(-1);
            if (id <= 0) continue;
            double ponderation = item.path("ponderation").asDouble(1.0);
            String type        = item.path("type").asText("BINAIRE");
            infos.put(id, new ItemInfo(id, ponderation, type));
        }
        log.debug("Grille {} : {} feuille(s) notable(s) chargée(s)", grilleId, infos.size());
        return infos;
    }

    private String currentBearerToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            if (jwt != null && jwt.getTokenValue() != null) {
                return jwt.getTokenValue();
            }
        }
        throw new BusinessException(
                "Calcul du score impossible : aucun JWT dans le contexte d'appel");
    }
}