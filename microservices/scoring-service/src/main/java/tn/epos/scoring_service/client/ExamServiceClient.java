package tn.epos.scoring_service.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tn.epos.common.exception.BusinessException;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-service read client for exam-service.
 *
 * <p>Used by NotationItemService to enforce that a NotationItem's {@code item_id}
 * belongs to the parent Notation's grille (closes #84). The check defends against
 * the offline-sync edge case where a Flutter client gets reassigned mid-day,
 * overwrites the cached station/grille IDs, but still has queued items tagged
 * to the old grille.
 *
 * <p>Cache: items don't move between grilles once created — verified against
 * {@code GrilleServiceImpl.modifierItem} (no grilleId in ItemRequest) and
 * {@code Examen.isGrilleModifiable} freeze. So a permanent ConcurrentHashMap is
 * safe; no TTL needed. The cache may go stale only if items are added to a
 * grille after first lookup — minor UX bug if it happens, no data corruption.
 */
@Component
public class ExamServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ExamServiceClient.class);

    // 500 is well above any realistic grille size (issue's data model allows ~20 items/grille).
    private static final int ITEMS_PAGE_SIZE = 500;

    private final WebClient webClient;
    private final ConcurrentHashMap<Long, Set<Long>> grilleItemsCache = new ConcurrentHashMap<>();

    public ExamServiceClient(@Value("${exam-service.base-url:http://localhost:8082}") String baseUrl) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * Returns the set of item IDs that belong to {@code grilleId}, cached
     * permanently after first fetch. Forwards the caller's JWT for authz.
     *
     * @throws BusinessException if exam-service is unreachable or returns an error
     *                           (fail-closed — grading already broken if exam-service is down)
     */
    public Set<Long> getItemIdsForGrille(Long grilleId) {
        return grilleItemsCache.computeIfAbsent(grilleId, this::fetchItemIds);
    }

    private Set<Long> fetchItemIds(Long grilleId) {
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

            return extractItemIds(root);
        } catch (WebClientResponseException e) {
            log.error("exam-service rejected items lookup for grille {} (status {}): {}",
                    grilleId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(
                    "Validation des items impossible : exam-service a renvoyé " + e.getStatusCode());
        } catch (RuntimeException e) {
            log.error("exam-service unreachable for grille {} items lookup", grilleId, e);
            throw new BusinessException(
                    "Validation des items impossible : exam-service injoignable");
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

    private String currentBearerToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            if (jwt != null && jwt.getTokenValue() != null) {
                return jwt.getTokenValue();
            }
        }
        // No JWT in context → can't call exam-service authoritatively. Fail-closed.
        throw new BusinessException(
                "Validation des items impossible : aucun JWT dans le contexte d'appel");
    }

    /** Test seam — allows tests to pre-populate the cache and skip the HTTP call. */
    void primeCacheForTest(Long grilleId, Set<Long> itemIds) {
        grilleItemsCache.put(grilleId, itemIds);
    }
}
