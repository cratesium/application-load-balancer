package com.example.loadbalancer.integration;

import com.example.loadbalancer.backend.BackendRegistry;
import com.example.loadbalancer.routing.AlgorithmManager;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import com.example.loadbalancer.testsupport.StubBackend;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that each algorithm changes observable routing behaviour end to end, over real HTTP.
 *
 * <p>The strategy unit tests already prove the selection maths. What these add is that the
 * behaviour survives the whole pipeline — configuration binding, the hot-swap mechanism, candidate
 * filtering and the proxy itself — which is the claim the requirements actually make: changing the
 * algorithm changes backend selection with no code change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AlgorithmBehaviourIntegrationTest {

    private static StubBackend backend1;
    private static StubBackend backend2;
    private static StubBackend backend3;

    private WebTestClient client;

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int albPort;

    @Autowired
    private AlgorithmManager algorithmManager;

    @Autowired
    private BackendRegistry registry;

    @BeforeAll
    static void startBackends() {
        backend1 = StubBackend.start("backend-1");
        backend2 = StubBackend.start("backend-2");
        backend3 = StubBackend.start("backend-3");
    }

    @AfterAll
    static void stopBackends() {
        backend1.close();
        backend2.close();
        backend3.close();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("load-balancer.listen.port", () -> "0");
        registry.add("load-balancer.backends[0].id", () -> "backend-1");
        registry.add("load-balancer.backends[0].host", backend1::host);
        registry.add("load-balancer.backends[0].port", backend1::port);
        registry.add("load-balancer.backends[1].id", () -> "backend-2");
        registry.add("load-balancer.backends[1].host", backend2::host);
        registry.add("load-balancer.backends[1].port", backend2::port);
        registry.add("load-balancer.backends[2].id", () -> "backend-3");
        registry.add("load-balancer.backends[2].host", backend3::host);
        registry.add("load-balancer.backends[2].port", backend3::port);
        registry.add("load-balancer.health-check.enabled", () -> "false");
        registry.add("load-balancer.retry.enabled", () -> "false");
        registry.add("load-balancer.admin.token", () -> "algorithm-test-token");
        registry.add("spring.test.webtestclient.timeout", () -> "30s");
    }

    @BeforeEach
    void reset() {
        client = com.example.loadbalancer.testsupport.TestClients.forPort(albPort, "algorithm-test");
        backend1.reset();
        backend2.reset();
        backend3.reset();
        registry.all().forEach(backend -> {
            registry.updateWeight(backend.id(), 1);
            registry.markHealthy(backend, "test setup");
        });
        algorithmManager.switchTo(LoadBalancingAlgorithm.ROUND_ROBIN);
    }

    private void send(int count) {
        for (int i = 0; i < count; i++) {
            client.get().uri("/api/test?i=" + i).exchange().expectStatus().isOk();
        }
    }

    @Test
    @DisplayName("ROUND_ROBIN spreads requests exactly evenly")
    void roundRobinIsEven() {
        send(30);

        assertThat(backend1.requestCount()).isEqualTo(10);
        assertThat(backend2.requestCount()).isEqualTo(10);
        assertThat(backend3.requestCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("WEIGHTED_ROUND_ROBIN follows the configured weights")
    void weightedRoundRobinFollowsWeights() {
        algorithmManager.switchTo(LoadBalancingAlgorithm.WEIGHTED_ROUND_ROBIN);
        registry.updateWeight("backend-1", 3);
        registry.updateWeight("backend-2", 1);
        registry.updateWeight("backend-3", 1);

        send(50);

        // 3:1:1 over a 5-slot schedule, so 30/10/10.
        assertThat(backend1.requestCount()).isEqualTo(30);
        assertThat(backend2.requestCount()).isEqualTo(10);
        assertThat(backend3.requestCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("RANDOM reaches every backend without being exactly even")
    void randomSpreadsAcrossAll() {
        algorithmManager.switchTo(LoadBalancingAlgorithm.RANDOM);

        send(90);

        assertThat(backend1.requestCount()).isPositive();
        assertThat(backend2.requestCount()).isPositive();
        assertThat(backend3.requestCount()).isPositive();
        assertThat(backend1.requestCount() + backend2.requestCount() + backend3.requestCount())
                .isEqualTo(90);
    }

    @Test
    @DisplayName("LEAST_CONNECTIONS moves traffic away from a slow backend")
    void leastConnectionsAvoidsSlowBackend() throws Exception {
        algorithmManager.switchTo(LoadBalancingAlgorithm.LEAST_CONNECTIONS);
        // backend-1 holds every request for 700ms, so its in-flight count stays high while the
        // others return immediately. This is the scenario round robin gets wrong.
        backend1.responseDelay(Duration.ofMillis(700));
        try {
            int threads = 12;
            int perThread = 5;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            try (var pool = Executors.newFixedThreadPool(threads)) {
                for (int t = 0; t < threads; t++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < perThread; i++) {
                                client.get().uri("/api/test").exchange().expectStatus().isOk();
                            }
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
            }

            int slow = backend1.requestCount();
            int fast = backend2.requestCount() + backend3.requestCount();

            // Round robin would have sent a third of the traffic to the slow backend. Least
            // connections sends it materially less, because its in-flight count stays elevated.
            assertThat(slow + fast).isEqualTo(threads * perThread);
            assertThat(slow).isLessThan(fast / 2);
        } finally {
            backend1.responseDelay(Duration.ZERO);
        }
    }

    @Test
    @DisplayName("IP_HASH pins one client to a single backend")
    void ipHashPinsClient() {
        algorithmManager.switchTo(LoadBalancingAlgorithm.IP_HASH);

        send(30);

        // All 30 requests come from the same source address, so exactly one backend serves them.
        long backendsUsed = java.util.stream.Stream.of(backend1, backend2, backend3)
                .filter(backend -> backend.requestCount() > 0)
                .count();
        assertThat(backendsUsed).isEqualTo(1);
    }

    @Test
    @DisplayName("CONSISTENT_HASH also pins one client, and survives a pool change")
    void consistentHashPinsClient() {
        algorithmManager.switchTo(LoadBalancingAlgorithm.CONSISTENT_HASH);

        send(20);
        String chosen = servingBackend();

        // Remove a backend that is NOT the one serving this client: with a hash ring, this
        // client must not move. Modulo hashing would remap it.
        String victim = java.util.stream.Stream.of("backend-1", "backend-2", "backend-3")
                .filter(id -> !id.equals(chosen))
                .findFirst()
                .orElseThrow();
        registry.markUnhealthy(registry.find(victim).orElseThrow(), "test");

        backend1.reset();
        backend2.reset();
        backend3.reset();
        send(20);

        assertThat(servingBackend()).isEqualTo(chosen);
    }

    @Test
    @DisplayName("WEIGHTED_LEAST_CONNECTIONS prefers the higher-capacity backend")
    void weightedLeastConnectionsPrefersCapacity() {
        algorithmManager.switchTo(LoadBalancingAlgorithm.WEIGHTED_LEAST_CONNECTIONS);
        registry.updateWeight("backend-1", 8);
        registry.updateWeight("backend-2", 1);
        registry.updateWeight("backend-3", 1);

        send(60);

        // Requests complete quickly, so in-flight counts stay near zero and the +1 in the
        // formula lets weight decide.
        assertThat(backend1.requestCount()).isGreaterThan(backend2.requestCount());
        assertThat(backend1.requestCount()).isGreaterThan(backend3.requestCount());
    }

    @Test
    @DisplayName("switching algorithms mid-traffic changes behaviour without dropping requests")
    void switchesAlgorithmMidTraffic() {
        send(9);
        assertThat(backend1.requestCount()).isEqualTo(3);

        algorithmManager.switchTo(LoadBalancingAlgorithm.IP_HASH);
        backend1.reset();
        backend2.reset();
        backend3.reset();

        send(12);

        long backendsUsed = java.util.stream.Stream.of(backend1, backend2, backend3)
                .filter(backend -> backend.requestCount() > 0)
                .count();
        assertThat(backendsUsed).isEqualTo(1);
    }

    @Test
    @DisplayName("every algorithm routes successfully with no code change")
    void allAlgorithmsWork() {
        for (LoadBalancingAlgorithm algorithm : LoadBalancingAlgorithm.values()) {
            backend1.reset();
            backend2.reset();
            backend3.reset();
            algorithmManager.switchTo(algorithm);

            send(6);

            int total = backend1.requestCount() + backend2.requestCount() + backend3.requestCount();
            assertThat(total)
                    .as("algorithm %s should have routed all 6 requests", algorithm)
                    .isEqualTo(6);
        }
    }

    /** @return the id of the single backend that received traffic. */
    private String servingBackend() {
        if (backend1.requestCount() > 0) {
            return "backend-1";
        }
        return backend2.requestCount() > 0 ? "backend-2" : "backend-3";
    }
}
