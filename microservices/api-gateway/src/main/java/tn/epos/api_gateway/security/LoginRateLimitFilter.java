package tn.epos.api_gateway.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client-IP rate limit on POST /api/v1/auth/login to blunt volumetric
 * credential-stuffing. Uses in-memory Bucket4j token buckets — no Redis
 * dependency. Per-account brute-force is separately bounded by auth-service's
 * 3-failed-attempt account lockout.
 */
@Component
public class LoginRateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimitFilter.class);

    private static final String LOGIN_PATH = "/api/v1/auth/login";

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final Duration refillPeriod;

    public LoginRateLimitFilter(
            @Value("${gateway.rate-limit.login.capacity:10}") int capacity,
            @Value("${gateway.rate-limit.login.refill-minutes:1}") long refillMinutes) {
        this.capacity = capacity;
        this.refillPeriod = Duration.ofMinutes(refillMinutes);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!LOGIN_PATH.equals(request.getURI().getPath())
                || !HttpMethod.POST.equals(request.getMethod())) {
            return chain.filter(exchange);
        }

        String clientIp = clientIp(request);
        Bucket bucket = buckets.computeIfAbsent(clientIp, ip -> newBucket());
        if (bucket.tryConsume(1)) {
            return chain.filter(exchange);
        }

        log.debug("Login rate limit hit from {}", clientIp);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"success\":false,\"message\":\"Too many login attempts, try again later\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.simple(capacity, refillPeriod))
                .build();
    }

    private String clientIp(ServerHttpRequest request) {
        return request.getRemoteAddress() != null
                ? request.getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }

    @Override
    public int getOrder() {
        // First in the chain — reject floods before any other work.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
