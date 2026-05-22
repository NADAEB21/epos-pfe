package tn.epos.api_gateway.security;

import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Validates the JWT on every routed request except the explicit public paths.
 *
 * On success it sets X-User-Id / X-User-Authorities from the verified claims so
 * downstream services can trust them. Any client-supplied copies of those
 * headers are stripped first — otherwise a caller could forge an identity.
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    static final String USER_ID_HEADER = "X-User-Id";
    static final String USER_AUTHORITIES_HEADER = "X-User-Authorities";

    /** auth-service endpoints reachable without a token (see CLAUDE.md). */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/password-reset/request",
            "/api/v1/auth/password-reset/confirm");

    private final GatewayJwtService jwtService;

    public JwtAuthenticationFilter(GatewayJwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Strip any client-supplied identity headers — only the gateway sets these.
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> {
                    h.remove(USER_ID_HEADER);
                    h.remove(USER_AUTHORITIES_HEADER);
                })
                .build();

        String path = request.getURI().getPath();
        if (PUBLIC_PATHS.contains(path)) {
            return chain.filter(exchange.mutate().request(request).build());
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "Authentication required");
        }

        Claims claims;
        try {
            claims = jwtService.parse(authHeader.substring(7));
        } catch (Exception e) {
            log.debug("JWT rejected for {}: {}", path, e.getMessage());
            return unauthorized(exchange, "Invalid or expired token");
        }

        Long userId = claims.get("userId", Long.class);
        @SuppressWarnings("unchecked")
        List<String> authorities = claims.get("authorities", List.class);
        if (userId == null || authorities == null) {
            return unauthorized(exchange, "Invalid or expired token");
        }

        ServerHttpRequest authenticated = request.mutate()
                .header(USER_ID_HEADER, String.valueOf(userId))
                .header(USER_AUTHORITIES_HEADER, String.join(",", authorities))
                .build();
        return chain.filter(exchange.mutate().request(authenticated).build());
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"success\":false,\"message\":\"" + message + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // After the rate-limit filter, before Spring Cloud Gateway routing.
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
