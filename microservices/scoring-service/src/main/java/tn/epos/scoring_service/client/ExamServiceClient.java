package tn.epos.scoring_service.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tn.epos.common.exception.BusinessException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ConcurrentHashMap;
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

    private final WebClient webClient;

    // Cache permanent : les pondérations ne changent pas une fois l'examen EN_COURS.
    private final ConcurrentHashMap<Long, Map<Long, ItemInfo>> grilleItemsCache =
            new ConcurrentHashMap<>();

    @Autowired
    public ExamServiceClient(
            @Value("${exam-service.base-url:http://localhost:8082}") String baseUrl) {
        this(WebClient.builder().baseUrl(baseUrl).build());
    }

    /** Package-private pour les tests — inject un WebClient stubbé. */
    ExamServiceClient(WebClient webClient) {
        this.webClient = webClient;
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

        try {
            Map<Long, ItemInfo> infos = fetchItemInfos(grilleId);
            if (!infos.isEmpty()) {
                // Mise en cache uniquement si la réponse est complète
                grilleItemsCache.put(grilleId, infos);
            }
            return infos;
        } catch (RuntimeException e) {
            log.warn("exam-service injoignable pour la grille {} — " +
                            "score sans pondérations, sera corrigé au prochain appel : {}",
                    grilleId, e.getMessage());
            return Collections.emptyMap(); // pas mis en cache → retentative automatique
        }
    }

    /** Compatibilité avec NotationItemService. */
    public Set<Long> getItemIdsForGrille(Long grilleId) {
        return getItemInfosForGrille(grilleId).keySet();
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
                    ? root.path("data").path("nom").asText("Station " + stationId)
                    : "Station " + stationId;
            return new StationInfo(nom);
        } catch (Exception e) {
            log.warn("exam-service injoignable pour station {} : {}", stationId, e.getMessage());
            return new StationInfo("Station " + stationId);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Implémentation
    // ─────────────────────────────────────────────────────────────────────────

    private Map<Long, ItemInfo> fetchItemInfos(Long grilleId) {
        String bearerToken = currentBearerToken();
        try {
            JsonNode root = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/grilles/{grilleId}/items")
                            .queryParam("size", ITEMS_PAGE_SIZE)
                            .build(grilleId))
                    .headers(h -> h.setBearerAuth(bearerToken))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            return extractItemInfos(root, grilleId);
        } catch (WebClientResponseException e) {
            log.error("exam-service HTTP {} pour grille {}", e.getStatusCode(), grilleId);
            throw new RuntimeException("exam-service HTTP " + e.getStatusCode(), e);
        } catch (RuntimeException e) {
            log.error("exam-service injoignable pour grille {}", grilleId, e);
            throw e;
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
            log.error("exam-service rejected exam lookup for {} (status {}): {}",
                    examenId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(
                    "Génération impossible : exam-service a renvoyé " + e.getStatusCode());
        } catch (RuntimeException e) {
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
                data.path("dateExamen").isTextual() ? LocalDate.parse(data.path("dateExamen").asText()) : null,
                data.path("heureDebut").isTextual() ? LocalTime.parse(data.path("heureDebut").asText()) : null,
                parseLaunchedAt(data.path("launchedAt")),
                data.path("dureeStationMin").isNumber() ? data.path("dureeStationMin").asInt() : null,
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
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return LocalDateTime.parse(node.asText(), LAUNCHED_AT_FMT);
        } catch (DateTimeParseException e) {
            log.warn("launched_at illisible ('{}') — fallback départ planifié", node.asText());
            return null;
        }
    }

    private Set<Long> extractItemIds(JsonNode root) {
        Set<Long> ids = new HashSet<>();
        if (root == null) return ids;

        // Response envelope: ApiResponse<PageResponse<ItemResponse>> →
        // {success, message, data: {content: [{id, ...}], ...}}
        JsonNode content = root.path("data").path("content");
        if (!content.isArray()) {
            log.warn("Unexpected exam-service response shape: missing data.content[]");
            return ids;
        }
        for (JsonNode item : content) {
            long id = item.path("id").asLong(-1);
            if (id > 0) ids.add(id);
        }
        return ids;
    }

        // Response envelope: ApiResponse<PageResponse<ItemResponse>> →
        // {success, message, data: {content: [{id, ...}], ...}}
    private Map<Long, ItemInfo> extractItemInfos(JsonNode root, Long grilleId) {
        Map<Long, ItemInfo> infos = new HashMap<>();
        if (root == null) {
            log.warn("Réponse null pour grille {}", grilleId);
            return infos;
        }
        // Enveloppe : ApiResponse<PageResponse<ItemResponse>>
        // → { success, data: { content: [{id, ponderation, type, …}] } }
        JsonNode content = root.path("data").path("content");
        if (!content.isArray()) {
            log.warn("Réponse inattendue de l'exam-service pour grille {} : data.content[] absent",
                    grilleId);
            return infos;
        }
        for (JsonNode item : content) {
            long id = item.path("id").asLong(-1);
            if (id <= 0) continue;
            double ponderation = item.path("ponderation").asDouble(1.0);
            String type        = item.path("type").asText("BINAIRE");
            infos.put(id, new ItemInfo(id, ponderation, type));
        }
        log.debug("Grille {} : {} item(s) chargé(s) depuis l'exam-service",
                grilleId, infos.size());
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