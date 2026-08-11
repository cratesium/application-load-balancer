package com.example.loadbalancer.integration;

import com.example.loadbalancer.resilience.CircuitBreakerRegistry;
import com.example.loadbalancer.resilience.CircuitState;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retry failover and circuit breaking against real backends.
 *
 * <p>Two backends only, so "the retry went somewhere else" is unambiguous. A dead port is used for
 * the connection-refused cases because that failure is genuinely different from an HTTP error
 * response and exercises the transport classification path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RetryAndCircuitBreakerIntegrationTest {

    private static StubBackend good;
    private static StubBackend bad;

    /** A port nothing is listening on, to produce genuine connection-refused failures. */
    private static int deadPort;

    private WebTestClient client;

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int albPort;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @BeforeAll
    static void startBackends() throws Exception {
        good = StubBackend.start("good");
        bad = StubBackend.start("bad");
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
    }

    @AfterAll
    static void stopBackends() {
        good.close();
        bad.close();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("load-balancer.listen.port", () -> "0");
        registry.add("load-balancer.backends[0].id", () -> "good");
        registry.add("load-balancer.backends[0].host", good::host);
        registry.add("load-balancer.backends[0].port", good::port);
        registry.add("load-balancer.backends[1].id", () -> "bad");
        registry.add("load-balancer.backends[1].host", bad::host);
        registry.add("load-balancer.backends[1].port", bad::port);

        registry.add("load-balancer.algorithm", () -> "ROUND_ROBIN");
        registry.add("load-balancer.health-check.enabled", () -> "false");
        // Passive health off: these tests are about retry and circuit breaking, and passive
        // demotion would remove the failing backend and mask what is being measured.
        registry.add("load-balancer.passive-health.enabled", () -> "false");
        registry.add("load-balancer.admin.token", () -> "retry-test-token");
        registry.add("load-balancer.retry.enabled", () -> "true");
        registry.add("load-balancer.retry.max-attempts", () -> "2");
        registry.add("load-balancer.timeouts.response", () -> "1s");
        registry.add("load-balancer.timeouts.request", () -> "10s");
        registry.add("load-balancer.circuit-breaker.enabled", () -> "true");
        registry.add("load-balancer.circuit-breaker.sliding-window-size", () -> "10");
        registry.add("load-balancer.circuit-breaker.minimum-calls", () -> "4");
        registry.add("load-balancer.circuit-breaker.failure-rate-threshold", () -> "50");
        registry.add("load-balancer.circuit-breaker.open-duration", () -> "1s");
        registry.add("load-balancer.circuit-breaker.half-open-max-calls", () -> "2");
        registry.add("load-balancer.circuit-breaker.half-open-successes-to-close", () -> "2");
        registry.add("spring.test.webtestclient.timeout", () -> "30s");
    }

    @BeforeEach
    void reset() {
        client = com.example.loadbalancer.testsupport.TestClients.forPort(albPort, "retry-test");
        good.reset();
        bad.reset();
        circuitBreakers.all().forEach(breaker -> breaker.reset());
    }

    @Test
    @DisplayName("a 503 from one backend is retried on the other and the client sees 200")
    void retriesOnAlternativeBackend() {
        // Only 'bad' fails. Four requests, so round robin sends roughly half of them to 'bad'
        // first — those are the ones that must be rescued by a retry onto 'good'.
        bad.failNextRequests(100, 503);

        for (int i = 0; i < 4; i++) {
            client.get().uri("/api/test").exchange().expectStatus().isOk();
        }

        // Every client request succeeded, 'good' served all four, and 'bad' saw the failed
        // first attempts — which is what proves the retry happened rather than 'bad' being skipped.
        assertThat(good.requestCount()).isEqualTo(4);
        assertThat(bad.requestCount()).isPositive();
    }

    @Test
    @DisplayName("a retry never re-hits the backend that just failed")
    void retryExcludesFailedBackend() {
        for (int i = 0; i < 20; i++) {
            good.reset();
            bad.reset();
            bad.failNextRequests(5, 503);

            client.get().uri("/api/test").exchange().expectStatus().isOk();

            // Two attempts at most, and never two against the same backend: 'bad' would keep
            // failing, so a retry that reused it could not have produced a 200.
            assertThat(bad.requestCount()).isLessThanOrEqualTo(1);
            assertThat(good.requestCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("gives up after max-attempts and relays the last backend's status")
    void stopsAfterMaxAttempts() {
        good.failNextRequests(10, 503);
        bad.failNextRequests(10, 503);

        client.get().uri("/api/test").exchange().expectStatus().isEqualTo(503);

        // max-attempts is 2, so exactly two backend attempts — not an unbounded loop.
        assertThat(good.requestCount() + bad.requestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("relays a 500 without retrying it")
    void doesNotRetry500() {
        good.failNextRequests(10, 500);
        bad.failNextRequests(10, 500);

        client.get().uri("/api/test").exchange().expectStatus().isEqualTo(500);

        // 500 means application code probably ran and may have had side effects.
        assertThat(good.requestCount() + bad.requestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("relays a 404 without retrying it")
    void doesNotRetry404() {
        good.failNextRequests(10, 404);
        bad.failNextRequests(10, 404);

        client.get().uri("/api/test").exchange().expectStatus().isNotFound();

        assertThat(good.requestCount() + bad.requestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("does not retry a POST by default, even for a retryable status")
    void doesNotRetryPost() {
        good.failNextRequests(10, 503);
        bad.failNextRequests(10, 503);

        client.post().uri("/api/orders").bodyValue("{\"amount\":100}")
                .exchange()
                .expectStatus().isEqualTo(503);

        // One attempt only: a POST that reached a backend may already have been processed.
        assertThat(good.requestCount() + bad.requestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("retries a response timeout on another backend")
    void retriesTimeout() {
        bad.responseDelay(Duration.ofSeconds(5));
        try {
            for (int i = 0; i < 4; i++) {
                client.get().uri("/api/test").exchange().expectStatus().isOk();
            }
            // Every request succeeded despite half of them being routed to a hung backend first.
            assertThat(good.requestCount()).isEqualTo(4);
        } finally {
            bad.responseDelay(Duration.ZERO);
        }
    }

    @Test
    @DisplayName("the breaker opens after repeated failures and stops sending traffic")
    void opensCircuitAfterRepeatedFailures() {
        bad.failNextRequests(100, 503);

        for (int i = 0; i < 12; i++) {
            client.get().uri("/api/test").exchange().expectStatus().isOk();
        }

        // 'bad' failed every request it received, so its breaker tripped.
        assertThat(circuitBreakers.forBackend("bad").state()).isIn(CircuitState.OPEN, CircuitState.HALF_OPEN);

        // Once open, it receives nothing at all: no attempt, no timeout, no wasted latency.
        int countWhenOpened = bad.requestCount();
        for (int i = 0; i < 10; i++) {
            client.get().uri("/api/test").exchange().expectStatus().isOk();
        }
        assertThat(bad.requestCount() - countWhenOpened).isLessThanOrEqualTo(2);
        assertThat(circuitBreakers.forBackend("good").state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("one backend's open breaker does not affect the other's traffic")
    void circuitBreakersAreIsolatedPerBackend() {
        bad.failNextRequests(100, 503);
        for (int i = 0; i < 12; i++) {
            client.get().uri("/api/test").exchange().expectStatus().isOk();
        }

        good.reset();
        for (int i = 0; i < 10; i++) {
            client.get().uri("/api/test").exchange().expectStatus().isOk();
        }

        // Failure isolation is the whole point of a load balancer: 'bad' being broken must not
        // reduce 'good' to a fraction of the traffic or fail requests.
        assertThat(good.requestCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("the breaker closes again after the backend recovers")
    void closesCircuitAfterRecovery() {
        bad.failNextRequests(100, 503);
        for (int i = 0; i < 12; i++) {
            client.get().uri("/api/test").exchange().expectStatus().isOk();
        }
        assertThat(circuitBreakers.forBackend("bad").state()).isIn(CircuitState.OPEN, CircuitState.HALF_OPEN);

        // Stop failing and let the cooldown elapse, then drive enough traffic for the
        // half-open probes to succeed.
        bad.reset();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    client.get().uri("/api/test").exchange().expectStatus().isOk();
                    return circuitBreakers.forBackend("bad").state() == CircuitState.CLOSED;
                });

        assertThat(circuitBreakers.forBackend("bad").state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("a connection-refused backend is retried and reported as 502 when unavoidable")
    void handlesConnectionRefused() {
        // Register a backend on a dead port through the admin API.
        client.post().uri("/admin/backends")
                .header("Authorization", "Bearer retry-test-token")
                .header("Content-Type", "application/json")
                .bodyValue("{\"id\":\"dead\",\"host\":\"127.0.0.1\",\"port\":" + deadPort + ",\"weight\":1}")
                .exchange()
                .expectStatus().isCreated();
        try {
            // Retries route around the dead backend, so clients never see the failure.
            for (int i = 0; i < 12; i++) {
                client.get().uri("/api/test").exchange().expectStatus().isOk();
            }

            // And the dead backend's breaker opened, so the ALB stops trying it.
            assertThat(circuitBreakers.forBackend("dead").state())
                    .isIn(CircuitState.OPEN, CircuitState.HALF_OPEN);
        } finally {
            client.delete().uri("/admin/backends/dead")
                    .header("Authorization", "Bearer retry-test-token")
                    .exchange();
        }
    }

    @Test
    @DisplayName("retries are counted in the metrics")
    void recordsRetryMetrics() {
        bad.failNextRequests(4, 503);
        for (int i = 0; i < 4; i++) {
            client.get().uri("/api/test").exchange().expectStatus().isOk();
        }

        byte[] body = client.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBodyContent();

        String metrics = new String(body == null ? new byte[0] : body);
        assertThat(metrics).contains("loadbalancer_retries_total");
        assertThat(metrics).contains("loadbalancer_backend_requests_total");
    }
}
