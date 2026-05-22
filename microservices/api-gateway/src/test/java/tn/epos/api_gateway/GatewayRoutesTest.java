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
    void allThreeServiceRoutesAreConfigured() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).isNotNull();
        assertThat(routes).extracting(Route::getId)
                .contains("auth-service", "exam-service", "scoring-service");
    }

    @Test
    void serviceRoutesTargetLoadBalancedUris() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).isNotNull();
        assertThat(routes)
                .filteredOn(r -> r.getId().endsWith("-service"))
                .allSatisfy(r -> assertThat(r.getUri().getScheme()).isEqualTo("lb"));
    }
}
