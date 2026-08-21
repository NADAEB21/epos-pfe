package tn.epos.api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class GatewayRoutesTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void allFourServiceRoutesAreConfigured() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).isNotNull();
        assertThat(routes).extracting(Route::getId)
                .contains("auth-service", "exam-service", "scoring-service", "ai-service");
    }

    @Test
    void eurekaBackedRoutesTargetLoadBalancedUris() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).isNotNull();
        assertThat(routes)
                .filteredOn(r -> List.of("auth-service", "exam-service", "scoring-service")
                        .contains(r.getId()))
                .allSatisfy(r -> assertThat(r.getUri().getScheme()).isEqualTo("lb"));
    }

    @Test
    void aiRouteIsDeliberatelyStatic() {
        // ADR-0029 D4 : pas de client Eureka Python — route statique vers le DNS
        // compose. Ce test fige le choix pour qu'un futur passage à lb:// soit un
        // acte, pas un accident.
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).isNotNull();
        Route ai = routes.stream().filter(r -> r.getId().equals("ai-service")).findFirst().orElseThrow();
        assertThat(ai.getUri().getScheme()).isEqualTo("http");
        assertThat(ai.getUri().getHost()).isEqualTo("ai-service");
        assertThat(ai.getUri().getPort()).isEqualTo(8084);
    }
}
