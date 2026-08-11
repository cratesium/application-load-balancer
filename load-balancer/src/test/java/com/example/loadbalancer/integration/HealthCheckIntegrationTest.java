package com.example.loadbalancer.integration;

import com.example.loadbalancer.backend.BackendRegistry;
import com.example.loadbalancer.backend.BackendState;
import com.example.loadbalancer.health.HealthCheckScheduler;
import com.example.loadbalancer.testsupport.StubBackend;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * Active health checking against real backends that can be made to fail.
 *
 * <p>The scheduler is driven manually via {@code runRound()} rather than by waiting for its timer.
 * That makes each transition an explicit, counted step instead of a sleep long enough to hope the
 * prober ran — the difference between a test that documents the thresholds and one that is flaky
 * on a busy CI machine.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthCheckIntegrationTest {

    private static StubBackend healthyBackend;
    private static StubBackend flakyBackend;

    private WebTestClient client;

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int albPort;

    @Autowired
    private BackendRegistry registry;

    @Autowired
    private HealthCheckScheduler scheduler;

    @BeforeAll
    static void startBackends() {
        healthyBackend = StubBackend.start("healthy");
        flakyBackend = StubBackend.start("flaky");
    }

    @AfterAll
    static void stopBackends() {
        healthyBackend.close();
        flakyBackend.close();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("load-balancer.listen.port", () -> "0");
        registry.add("load-balancer.backends[0].id", () -> "healthy");
        registry.add("load-balancer.backends[0].host", healthyBackend::host);
        registry.add("load-balancer.backends[0].port", healthyBackend::port);
        registry.add("load-balancer.backends[1].id", () -> "flaky");
        registry.add("load-balancer.backends[1].host", flakyBackend::host);
        registry.add("load-balancer.backends[1].port", flakyBackend::port);

        registry.add("load-balancer.health-check.enabled", () -> "true");
        // A very long interval so the background timer never fires during the test; rounds are
        // triggered explicitly instead.
        registry.add("load-balancer.health-check.interval", () -> "1h");
        registry.add("load-balancer.health-check.failure-threshold", () -> "3");
        registry.add("load-balancer.health-check.success-threshold", () -> "2");
        registry.add("load-balancer.health-check.response-timeout", () -> "2s");
        registry.add("load-balancer.admin.token", () -> "health-test-token");
        registry.add("load-balancer.retry.enabled", () -> "false");
        registry.add("spring.test.webtestclient.timeout", () -> "30s");
    }

    @org.junit.jupiter.api.BeforeEach
    void createClient() {
        client = com.example.loadbalancer.testsupport.TestClients.forPort(albPort, "health-test");
    }

    private void runProbeRound() {
        scheduler.runRound().block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("healthy -> unhealthy takes exactly failure-threshold rounds, then routing stops")
    void demotesAfterFailureThreshold() {
        try {
            assertThat(state("flaky")).isEqualTo(BackendState.UP);
            flakyBackend.healthy(false);

            runProbeRound();
            assertThat(state("flaky")).isEqualTo(BackendState.UP);

            runProbeRound();
            // Still UP: two failed probes are not yet evidence of an outage.
            assertThat(state("flaky")).isEqualTo(BackendState.UP);

            runProbeRound();
            assertThat(state("flaky")).isEqualTo(BackendState.DOWN);

            // And now no traffic reaches it, however many requests arrive.
            flakyBackend.reset();
            flakyBackend.healthy(false);
            for (int i = 0; i < 10; i++) {
                client.get().uri("/api/test").exchange().expectStatus().isOk();
            }
            assertThat(flakyBackend.requestCount()).isZero();
            assertThat(healthyBackend.requestCount()).isEqualTo(10);
        } finally {
            restore();
        }
    }

    @Test
    @DisplayName("unhealthy -> healthy takes exactly success-threshold rounds")
    void promotesAfterSuccessThreshold() {
        try {
            flakyBackend.healthy(false);
            runProbeRound();
            runProbeRound();
            runProbeRound();
            assertThat(state("flaky")).isEqualTo(BackendState.DOWN);

            flakyBackend.healthy(true);

            runProbeRound();
            // One good probe is not recovery: a flapping backend must not be re-admitted on
            // a single lucky response.
            assertThat(state("flaky")).isEqualTo(BackendState.DOWN);

            runProbeRound();
            assertThat(state("flaky")).isEqualTo(BackendState.UP);
        } finally {
            restore();
        }
    }

    @Test
    @DisplayName("a success part-way through resets the failure count")
    void interleavedSuccessPreventsDemotion() {
        try {
            flakyBackend.healthy(false);
            runProbeRound();
            runProbeRound();

            flakyBackend.healthy(true);
            runProbeRound();

            flakyBackend.healthy(false);
            runProbeRound();
            runProbeRound();

            // Five failures overall, but never three consecutively.
            assertThat(state("flaky")).isEqualTo(BackendState.UP);
        } finally {
            restore();
        }
    }

    @Test
    @DisplayName("returns 503 with the documented body when every backend is down")
    void returns503WhenAllBackendsAreDown() {
        try {
            healthyBackend.healthy(false);
            flakyBackend.healthy(false);
            for (int i = 0; i < 3; i++) {
                runProbeRound();
            }

            client.get().uri("/api/test")
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectHeader().exists("Retry-After")
                    .expectBody()
                    .jsonPath("$.error").isEqualTo("NO_HEALTHY_BACKEND")
                    .jsonPath("$.message").isEqualTo(
                            "No healthy backend servers are currently available (2 backend(s) in pool)")
                    .jsonPath("$.requestId").exists()
                    .jsonPath("$.status").isEqualTo(503);
        } finally {
            restore();
        }
    }

    @Test
    @DisplayName("a health probe cannot resurrect an administratively disabled backend")
    void probesDoNotOverrideOperator() {
        try {
            client.post().uri("/admin/backends/flaky/disable")
                    .header("Authorization", "Bearer health-test-token")
                    .exchange()
                    .expectStatus().isAccepted();

            // Perfectly healthy, probed repeatedly, and still not routable — because a human
            // took it out of service.
            flakyBackend.healthy(true);
            for (int i = 0; i < 5; i++) {
                runProbeRound();
            }

            assertThat(state("flaky")).isIn(BackendState.DRAINING, BackendState.DISABLED);
            assertThat(registry.routable()).extracting(b -> b.id()).containsExactly("healthy");
        } finally {
            client.post().uri("/admin/backends/flaky/enable")
                    .header("Authorization", "Bearer health-test-token")
                    .exchange()
                    .expectStatus().isOk();
            restore();
        }
    }

    @Test
    @DisplayName("the ALB reports itself not-ready only when it cannot serve")
    void exposesReadinessProbe() {
        client.get().uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    private BackendState state(String id) {
        return registry.find(id).orElseThrow().state();
    }

    /** Returns both backends to a known-good UP state for the next test. */
    private void restore() {
        healthyBackend.reset();
        flakyBackend.reset();
        registry.all().forEach(backend -> {
            if (backend.state() != BackendState.UP) {
                registry.markHealthy(backend, "test cleanup");
            }
        });
        runProbeRound();
        runProbeRound();
        registry.all().forEach(backend -> registry.markHealthy(backend, "test cleanup"));
        healthyBackend.reset();
        flakyBackend.reset();
    }
}
