package com.example.loadbalancer.routing;

import com.example.loadbalancer.backend.BackendRegistry;
import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.config.LoadBalancerProperties;
import com.example.loadbalancer.exception.NoHealthyBackendException;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import com.example.loadbalancer.resilience.CircuitBreakerRegistry;
import com.example.loadbalancer.testsupport.TestBackends;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackendSelectionServiceTest {

    private record Fixture(BackendSelectionService selection,
                           BackendRegistry registry,
                           CircuitBreakerRegistry breakers,
                           AlgorithmManager algorithms) {
    }

    private Fixture fixture(List<LoadBalancerProperties.Route> routes) {
        return fixture(routes, LoadBalancingAlgorithm.ROUND_ROBIN);
    }

    private Fixture fixture(List<LoadBalancerProperties.Route> routes, LoadBalancingAlgorithm algorithm) {
        LoadBalancerProperties properties = TestBackends
                .propertiesBuilder(algorithm, List.of(
                        TestBackends.backendConfig("backend-1", "host-1", 8080, 1),
                        TestBackends.backendConfig("backend-2", "host-2", 8081, 1),
                        TestBackends.backendConfig("backend-3", "host-3", 8082, 1)))
                .routes(routes)
                .circuitBreaker(true, 10, 2, 50, Duration.ofSeconds(10), 3, 2)
                .build();

        BackendRegistry registry = new BackendRegistry(properties);
        RouteRegistry routeRegistry = new RouteRegistry(properties);
        LoadBalancingStrategyFactory factory = new LoadBalancingStrategyFactory(List.of(
                new RoundRobinStrategy(),
                new WeightedRoundRobinStrategy(),
                new RandomStrategy(),
                new LeastConnectionsStrategy(),
                new WeightedLeastConnectionsStrategy(),
                new IpHashStrategy(),
                new ConsistentHashStrategy(properties)));
        AlgorithmManager algorithms = new AlgorithmManager(properties, factory);
        CircuitBreakerRegistry breakers = new CircuitBreakerRegistry(properties, registry);

        return new Fixture(
                new BackendSelectionService(registry, routeRegistry, algorithms, breakers),
                registry, breakers, algorithms);
    }

    private LoadBalancingContext context(String method, String path, Set<String> excluded) {
        return new LoadBalancingContext("req-1", method, path, "203.0.113.1", "203.0.113.1",
                1, excluded, 0L);
    }

    @Test
    @DisplayName("selects from the global pool when no route matches")
    void usesGlobalPoolByDefault() {
        Fixture fixture = fixture(List.of());

        BackendSelectionService.Selection selection = fixture.selection()
                .select(context("GET", "/api/test", Set.of()));

        assertThat(selection.routeId()).isEqualTo(BackendSelectionService.DEFAULT_ROUTE);
        assertThat(selection.candidates()).isEqualTo(3);
        assertThat(selection.algorithm()).isEqualTo(LoadBalancingAlgorithm.ROUND_ROBIN);
    }

    @Test
    @DisplayName("restricts selection to a matched route's backend pool")
    void restrictsToRoutePool() {
        Fixture fixture = fixture(List.of(new LoadBalancerProperties.Route(
                "users", "/api/users/**", Set.of(), List.of("backend-3"), null)));

        for (int i = 0; i < 10; i++) {
            BackendSelectionService.Selection selection = fixture.selection()
                    .select(context("GET", "/api/users/123", Set.of()));
            assertThat(selection.backend().id()).isEqualTo("backend-3");
            assertThat(selection.routeId()).isEqualTo("users");
        }
    }

    @Test
    @DisplayName("method-qualified routes send GET and POST to different pools")
    void routesByMethod() {
        Fixture fixture = fixture(List.of(
                new LoadBalancerProperties.Route("orders-read", "/api/orders/**",
                        Set.of("GET"), List.of("backend-1"), null),
                new LoadBalancerProperties.Route("orders-write", "/api/orders/**",
                        Set.of("POST", "PUT"), List.of("backend-2"), null)));

        assertThat(fixture.selection().select(context("GET", "/api/orders/1", Set.of())).backend().id())
                .isEqualTo("backend-1");
        assertThat(fixture.selection().select(context("POST", "/api/orders", Set.of())).backend().id())
                .isEqualTo("backend-2");
        // No rule matches DELETE, so it falls through to the global pool.
        assertThat(fixture.selection().select(context("DELETE", "/api/orders/1", Set.of())).routeId())
                .isEqualTo(BackendSelectionService.DEFAULT_ROUTE);
    }

    @Test
    @DisplayName("a per-route algorithm override beats the global algorithm")
    void appliesPerRouteAlgorithm() {
        Fixture fixture = fixture(List.of(new LoadBalancerProperties.Route(
                "sticky", "/api/session/**", Set.of(), List.of(),
                LoadBalancingAlgorithm.CONSISTENT_HASH)));

        BackendSelectionService.Selection selection = fixture.selection()
                .select(context("GET", "/api/session/abc", Set.of()));

        assertThat(selection.algorithm()).isEqualTo(LoadBalancingAlgorithm.CONSISTENT_HASH);
        assertThat(fixture.selection().select(context("GET", "/other", Set.of())).algorithm())
                .isEqualTo(LoadBalancingAlgorithm.ROUND_ROBIN);
    }

    @Test
    @DisplayName("a matched route with no healthy backends fails instead of using the global pool")
    void routeDoesNotFallBackToGlobalPool() {
        Fixture fixture = fixture(List.of(new LoadBalancerProperties.Route(
                "orders", "/api/orders/**", Set.of(), List.of("backend-3"), null)));
        fixture.registry().markUnhealthy(fixture.registry().find("backend-3").orElseThrow(), "test");

        // Silently routing /api/orders to the users service would be far worse than a 503.
        assertThatThrownBy(() -> fixture.selection().select(context("GET", "/api/orders/1", Set.of())))
                .isInstanceOf(NoHealthyBackendException.class);

        // The global pool is still perfectly healthy for other paths.
        assertThat(fixture.selection().select(context("GET", "/other", Set.of())).backend()).isNotNull();
    }

    @Test
    @DisplayName("excludes backends already tried on this request")
    void excludesTriedBackends() {
        Fixture fixture = fixture(List.of());

        BackendSelectionService.Selection selection = fixture.selection()
                .select(context("GET", "/api/test", Set.of("backend-1", "backend-2")));

        assertThat(selection.backend().id()).isEqualTo("backend-3");
        assertThat(selection.candidates()).isEqualTo(1);
    }

    @Test
    @DisplayName("excludes backends whose circuit breaker is open")
    void excludesOpenCircuits() {
        Fixture fixture = fixture(List.of());
        // Trip backend-1's breaker: minimum-calls 2, threshold 50%.
        for (int i = 0; i < 4; i++) {
            fixture.breakers().tryAcquire("backend-1");
            fixture.breakers().recordFailure("backend-1");
        }
        assertThat(fixture.breakers().isAvailable("backend-1")).isFalse();

        for (int i = 0; i < 20; i++) {
            assertThat(fixture.selection().select(context("GET", "/api/test", Set.of())).backend().id())
                    .isNotEqualTo("backend-1");
        }
    }

    @Test
    @DisplayName("503 when nothing is eligible")
    void failsWhenNothingIsEligible() {
        Fixture fixture = fixture(List.of());
        fixture.registry().all().forEach(backend ->
                fixture.registry().markUnhealthy(backend, "test"));

        assertThatThrownBy(() -> fixture.selection().select(context("GET", "/api/test", Set.of())))
                .isInstanceOf(NoHealthyBackendException.class)
                .hasMessageContaining("3 backend(s) in pool");
    }

    @Test
    @DisplayName("hasAlternative reports whether a retry has anywhere to go")
    void reportsAlternatives() {
        Fixture fixture = fixture(List.of());

        assertThat(fixture.selection().hasAlternative(
                context("GET", "/api/test", Set.of()), "backend-1")).isTrue();
        assertThat(fixture.selection().hasAlternative(
                context("GET", "/api/test", Set.of("backend-2", "backend-3")), "backend-1")).isFalse();
    }

    @Test
    @DisplayName("the candidate list preserves registry order so strategy caches stay valid")
    void preservesRegistryOrder() {
        Fixture fixture = fixture(List.of(), LoadBalancingAlgorithm.ROUND_ROBIN);

        // Round robin walking a stable order produces the documented rotation. An unstable
        // order would also invalidate the weighted-schedule and hash-ring caches every request.
        assertThat(List.of(
                fixture.selection().select(context("GET", "/x", Set.of())).backend().id(),
                fixture.selection().select(context("GET", "/x", Set.of())).backend().id(),
                fixture.selection().select(context("GET", "/x", Set.of())).backend().id()))
                .containsExactly("backend-1", "backend-2", "backend-3");
    }

    @Test
    @DisplayName("switching the algorithm changes selection with no restart")
    void algorithmSwitchTakesEffectImmediately() {
        Fixture fixture = fixture(List.of());
        BackendServer backend1 = fixture.registry().find("backend-1").orElseThrow();
        for (int i = 0; i < 5; i++) {
            backend1.acquireConnection();
        }

        assertThat(fixture.selection().select(context("GET", "/x", Set.of())).algorithm())
                .isEqualTo(LoadBalancingAlgorithm.ROUND_ROBIN);

        fixture.algorithms().switchTo(LoadBalancingAlgorithm.LEAST_CONNECTIONS);

        BackendSelectionService.Selection selection = fixture.selection()
                .select(context("GET", "/x", Set.of()));
        assertThat(selection.algorithm()).isEqualTo(LoadBalancingAlgorithm.LEAST_CONNECTIONS);
        assertThat(selection.backend().id()).isNotEqualTo("backend-1");
    }
}
