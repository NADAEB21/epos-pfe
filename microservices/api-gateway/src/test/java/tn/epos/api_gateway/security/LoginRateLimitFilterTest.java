package tn.epos.api_gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimitFilterTest {

    private static final GatewayFilterChain PASS_THROUGH = exchange -> Mono.empty();

    @Test
    void allowsUpToCapacityThenReturns429() {
        LoginRateLimitFilter filter = new LoginRateLimitFilter(3, 1);

        for (int i = 0; i < 3; i++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/auth/login"));
            filter.filter(exchange, PASS_THROUGH).block();
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }

        MockServerWebExchange blocked = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login"));
        filter.filter(blocked, PASS_THROUGH).block();
        assertThat(blocked.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void doesNotRateLimitNonLoginPaths() {
        LoginRateLimitFilter filter = new LoginRateLimitFilter(1, 1);

        for (int i = 0; i < 5; i++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/users"));
            filter.filter(exchange, PASS_THROUGH).block();
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }

    @Test
    void doesNotRateLimitNonPostLoginRequests() {
        LoginRateLimitFilter filter = new LoginRateLimitFilter(1, 1);

        for (int i = 0; i < 5; i++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/auth/login"));
            filter.filter(exchange, PASS_THROUGH).block();
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }
    }
}
