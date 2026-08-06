package com.example.loadbalancer.testsupport;

import com.example.loadbalancer.backend.BackendHealth;
import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.backend.BackendState;
import com.example.loadbalancer.config.LoadBalancerProperties;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import com.example.loadbalancer.routing.LoadBalancingContext;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builders for the objects every test needs, so tests state only what they care about.
 *
 * <p>{@link LoadBalancerProperties} has a large surface; constructing it inline in each test
 * would bury the one field under test in forty defaults and make every future config change a
 * mass test edit.
 */
public final class TestBackends {

    private TestBackends() {
    }

    /** A routable backend with the given id and weight. */
    public static BackendServer backend(String id, int weight) {
        return new BackendServer(id, "host-" + id, 8080, false, weight,
                BackendState.UP, new BackendHealth(3, 2));
    }

    /** A routable backend with weight 1. */
    public static BackendServer backend(String id) {
        return backend(id, 1);
    }

    /** A backend with a specific number of in-flight requests already counted. */
    public static BackendServer backendWithConnections(String id, int weight, int activeConnections) {
        BackendServer backend = backend(id, weight);
        for (int i = 0; i < activeConnections; i++) {
            backend.acquireConnection();
        }
        return backend;
    }

    /** {@code n} backends named {@code backend-1 .. backend-n}, all weight 1. */
    public static List<BackendServer> backends(int count) {
        List<BackendServer> backends = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            backends.add(backend("backend-" + i));
        }
        return backends;
    }

    /** A routing context with the given affinity key and no exclusions. */
    public static LoadBalancingContext context(String affinityKey) {
        return new LoadBalancingContext("req-1", "GET", "/api/test", affinityKey, affinityKey,
                1, Set.of(), 1L);
    }

    /** A routing context pinned to a pool version, for cache-invalidation tests. */
    public static LoadBalancingContext context(String affinityKey, long poolVersion) {
        return new LoadBalancingContext("req-1", "GET", "/api/test", affinityKey, affinityKey,
                1, Set.of(), poolVersion);
    }

    /** Default properties with the supplied backends and algorithm. */
    public static LoadBalancerProperties properties(LoadBalancingAlgorithm algorithm,
                                                    List<LoadBalancerProperties.Backend> backends) {
        return propertiesBuilder(algorithm, backends).build();
    }

    public static Builder propertiesBuilder(LoadBalancingAlgorithm algorithm,
                                            List<LoadBalancerProperties.Backend> backends) {
        return new Builder(algorithm, backends);
    }

    public static LoadBalancerProperties.Backend backendConfig(String id, String host, int port, int weight) {
        return new LoadBalancerProperties.Backend(id, host, port, false, weight, true);
    }

    /**
     * Mutable builder over the immutable properties record, exposing only the fields tests
     * actually vary.
     */
    public static final class Builder {

        private final LoadBalancingAlgorithm algorithm;
        private final List<LoadBalancerProperties.Backend> backends;
        private List<LoadBalancerProperties.Route> routes = List.of();
        private boolean healthCheckEnabled = false;
        private int failureThreshold = 3;
        private int successThreshold = 2;
        private boolean assumeHealthyOnStart = true;
        private boolean passiveHealthEnabled = true;
        private int passiveFailureThreshold = 5;
        private Duration passiveWindow = Duration.ofSeconds(30);
        private boolean retryEnabled = true;
        private int maxAttempts = 2;
        private Set<String> retryMethods = Set.of("GET", "HEAD", "OPTIONS");
        private Set<Integer> retryStatuses = Set.of(502, 503, 504);
        private boolean bufferRequestBody = false;
        private boolean circuitBreakerEnabled = true;
        private int slidingWindowSize = 20;
        private int minimumCalls = 10;
        private int failureRateThreshold = 50;
        private Duration openDuration = Duration.ofSeconds(10);
        private int halfOpenMaxCalls = 3;
        private int halfOpenSuccessesToClose = 2;
        private int virtualNodes = 100;
        private int maxConcurrentRequests = 10_000;
        private List<String> trustedProxies = List.of();
        private Duration responseTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private String adminToken = "test-token";

        private Builder(LoadBalancingAlgorithm algorithm, List<LoadBalancerProperties.Backend> backends) {
            this.algorithm = algorithm;
            this.backends = backends;
        }

        public Builder routes(List<LoadBalancerProperties.Route> routes) {
            this.routes = routes;
            return this;
        }

        public Builder healthCheck(boolean enabled, int failureThreshold, int successThreshold) {
            this.healthCheckEnabled = enabled;
            this.failureThreshold = failureThreshold;
            this.successThreshold = successThreshold;
            return this;
        }

        public Builder assumeHealthyOnStart(boolean value) {
            this.assumeHealthyOnStart = value;
            return this;
        }

        public Builder passiveHealth(boolean enabled, int failureThreshold, Duration window) {
            this.passiveHealthEnabled = enabled;
            this.passiveFailureThreshold = failureThreshold;
            this.passiveWindow = window;
            return this;
        }

        public Builder retry(boolean enabled, int maxAttempts, Set<String> methods) {
            this.retryEnabled = enabled;
            this.maxAttempts = maxAttempts;
            this.retryMethods = methods;
            return this;
        }

        public Builder retryStatuses(Set<Integer> statuses) {
            this.retryStatuses = statuses;
            return this;
        }

        public Builder bufferRequestBody(boolean value) {
            this.bufferRequestBody = value;
            return this;
        }

        public Builder circuitBreaker(boolean enabled, int windowSize, int minimumCalls,
                                      int failureRateThreshold, Duration openDuration,
                                      int halfOpenMaxCalls, int halfOpenSuccessesToClose) {
            this.circuitBreakerEnabled = enabled;
            this.slidingWindowSize = windowSize;
            this.minimumCalls = minimumCalls;
            this.failureRateThreshold = failureRateThreshold;
            this.openDuration = openDuration;
            this.halfOpenMaxCalls = halfOpenMaxCalls;
            this.halfOpenSuccessesToClose = halfOpenSuccessesToClose;
            return this;
        }

        public Builder virtualNodes(int virtualNodes) {
            this.virtualNodes = virtualNodes;
            return this;
        }

        public Builder maxConcurrentRequests(int value) {
            this.maxConcurrentRequests = value;
            return this;
        }

        public Builder trustedProxies(List<String> cidrs) {
            this.trustedProxies = cidrs;
            return this;
        }

        public Builder timeouts(Duration response, Duration request) {
            this.responseTimeout = response;
            this.requestTimeout = request;
            return this;
        }

        public Builder adminToken(String token) {
            this.adminToken = token;
            return this;
        }

        public LoadBalancerProperties build() {
            return new LoadBalancerProperties(
                    new LoadBalancerProperties.Listen("0.0.0.0", 8080),
                    algorithm,
                    backends,
                    routes,
                    new LoadBalancerProperties.ConsistentHash(virtualNodes),
                    new LoadBalancerProperties.HealthCheck(healthCheckEnabled, "/health",
                            Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(3),
                            Duration.ZERO, failureThreshold, successThreshold, Set.of(),
                            assumeHealthyOnStart),
                    new LoadBalancerProperties.PassiveHealth(passiveHealthEnabled,
                            passiveFailureThreshold, passiveWindow),
                    new LoadBalancerProperties.Timeouts(Duration.ofSeconds(2), responseTimeout,
                            requestTimeout, Duration.ofSeconds(60)),
                    new LoadBalancerProperties.ConnectionPool(500, Duration.ofSeconds(5),
                            Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMinutes(2)),
                    new LoadBalancerProperties.Retry(retryEnabled, maxAttempts, retryMethods,
                            retryStatuses, Duration.ofMillis(1), bufferRequestBody,
                            DataSize.ofKilobytes(256)),
                    new LoadBalancerProperties.CircuitBreaker(circuitBreakerEnabled, slidingWindowSize,
                            minimumCalls, failureRateThreshold, openDuration, halfOpenMaxCalls,
                            halfOpenSuccessesToClose),
                    new LoadBalancerProperties.Draining(Duration.ofSeconds(5), Duration.ofMillis(50)),
                    new LoadBalancerProperties.Shutdown(Duration.ofSeconds(5)),
                    new LoadBalancerProperties.Limits(DataSize.ofMegabytes(10), DataSize.ofKilobytes(16),
                            DataSize.ofKilobytes(8), maxConcurrentRequests, 5000),
                    new LoadBalancerProperties.Admin(true, adminToken, "/admin"),
                    new LoadBalancerProperties.Proxy(false, true, trustedProxies, "X-Request-ID"));
        }
    }
}
