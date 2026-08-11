package com.example.loadbalancer.integration;

import com.example.loadbalancer.backend.BackendRegistry;
import com.example.loadbalancer.testsupport.StubBackend;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin API tests: authentication, dynamic backend management, and hot algorithm switching.
 *
 * <p>The authentication tests matter as much as the functional ones. This API can point traffic at
 * an attacker-controlled host or disable every backend, so "it rejects requests without a token"
 * is a correctness requirement, not a nicety.
 *
 * <p>{@code admin} is the injected client with the bearer token pre-attached; {@code client} is the
 * anonymous one, used for proxied traffic and for the authentication tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminApiIntegrationTest {

    private static final String TOKEN = "admin-integration-test-token-value";

    private static StubBackend backend1;
    private static StubBackend backend2;
    private static StubBackend spare;

    /** Anonymous client, for proxied traffic and the authentication tests. */
    private WebTestClient client;

    /** Client with the bearer token attached. */
    private WebTestClient admin;

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int albPort;

    @Autowired
    private BackendRegistry registry;

    @BeforeAll
    static void startBackends() {
        backend1 = StubBackend.start("backend-1");
        backend2 = StubBackend.start("backend-2");
        spare = StubBackend.start("spare");
    }

    @AfterAll
    static void stopBackends() {
        backend1.close();
        backend2.close();
        spare.close();
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
        registry.add("load-balancer.algorithm", () -> "ROUND_ROBIN");
        registry.add("load-balancer.health-check.enabled", () -> "false");
        registry.add("load-balancer.admin.token", () -> TOKEN);
        registry.add("load-balancer.draining.timeout", () -> "2s");
        registry.add("load-balancer.draining.check-interval", () -> "50ms");
        registry.add("spring.test.webtestclient.timeout", () -> "30s");
    }

    @BeforeEach
    void resetState() {
        client = com.example.loadbalancer.testsupport.TestClients.forPort(albPort, "admin-test");
        admin = client.mutate()
                .defaultHeader("Authorization", "Bearer " + TOKEN)
                .build();

        backend1.reset();
        backend2.reset();
        spare.reset();
        registry.find("spare-backend").ifPresent(backend -> registry.unregister("spare-backend"));
        registry.all().forEach(backend -> registry.markHealthy(backend, "test setup"));
        setAlgorithm("ROUND_ROBIN");
        backend1.reset();
        backend2.reset();
    }

    private void setAlgorithm(String algorithm) {
        admin.post().uri("/admin/load-balancer/algorithm")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("algorithm", algorithm))
                .exchange()
                .expectStatus().isOk();
    }

    // ------------------------------------------------------------------
    // Authentication
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rejects an admin request with no token")
    void rejectsMissingToken() {
        client.get().uri("/admin/status")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("WWW-Authenticate")
                .expectBody().jsonPath("$.error").isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("rejects a wrong token, a prefix of the real token, and a missing Bearer scheme")
    void rejectsWrongToken() {
        client.get().uri("/admin/status")
                .header("Authorization", "Bearer not-the-token")
                .exchange()
                .expectStatus().isUnauthorized();

        // A prefix must not be accepted: the comparison covers the whole value and is
        // constant-time, so it leaks neither content nor length.
        client.get().uri("/admin/status")
                .header("Authorization", "Bearer " + TOKEN.substring(0, TOKEN.length() - 1))
                .exchange()
                .expectStatus().isUnauthorized();

        client.get().uri("/admin/status")
                .header("Authorization", TOKEN)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("protects every mutating admin endpoint, not just the read ones")
    void protectsMutatingEndpoints() {
        // The realistic attack: register a backend you control and receive the traffic.
        client.post().uri("/admin/backends")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("id", "evil", "host", "attacker.example.com", "port", 80))
                .exchange()
                .expectStatus().isUnauthorized();

        client.delete().uri("/admin/backends/backend-1").exchange().expectStatus().isUnauthorized();
        client.post().uri("/admin/backends/backend-1/disable").exchange().expectStatus().isUnauthorized();
        client.post().uri("/admin/config/reload").exchange().expectStatus().isUnauthorized();

        assertThat(registry.all()).hasSize(2);
        assertThat(registry.find("evil")).isEmpty();
    }

    @Test
    @DisplayName("proxied traffic needs no token")
    void proxyPathIsUnauthenticated() {
        client.get().uri("/api/test").exchange().expectStatus().isOk();
    }

    // ------------------------------------------------------------------
    // Status and inspection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /admin/status summarises the load balancer")
    void reportsStatus() {
        client.get().uri("/api/test").exchange().expectStatus().isOk();

        admin.get().uri("/admin/status")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.algorithm").isEqualTo("ROUND_ROBIN")
                .jsonPath("$.totalBackends").isEqualTo(2)
                .jsonPath("$.healthyBackends").isEqualTo(2)
                .jsonPath("$.activeRequests").isEqualTo(0)
                .jsonPath("$.acceptingTraffic").isEqualTo(true)
                .jsonPath("$.uptimeSeconds").exists();
    }

    @Test
    @DisplayName("GET /admin/backends returns DTOs, never internal state")
    void listsBackends() {
        admin.get().uri("/admin/backends")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].id").isEqualTo("backend-1")
                .jsonPath("$[0].status").isEqualTo("UP")
                .jsonPath("$[0].weight").isEqualTo(1)
                .jsonPath("$[0].activeConnections").isEqualTo(0)
                .jsonPath("$[0].circuitState").exists();
    }

    @Test
    @DisplayName("GET /admin/algorithm lists the active and supported algorithms")
    void reportsAlgorithms() {
        admin.get().uri("/admin/algorithm")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.algorithm").isEqualTo("ROUND_ROBIN")
                .jsonPath("$.supported.length()").isEqualTo(7);
    }

    // ------------------------------------------------------------------
    // Algorithm switching
    // ------------------------------------------------------------------

    @Test
    @DisplayName("switching the algorithm changes routing behaviour with no restart")
    void switchesAlgorithmAtRuntime() {
        admin.post().uri("/admin/load-balancer/algorithm")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("algorithm", "IP_HASH"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.previousAlgorithm").isEqualTo("ROUND_ROBIN")
                .jsonPath("$.currentAlgorithm").isEqualTo("IP_HASH");

        for (int i = 0; i < 8; i++) {
            client.get().uri("/api/test").exchange().expectStatus().isOk();
        }

        // IP_HASH pins one client to one backend, where ROUND_ROBIN alternated.
        assertThat(backend1.requestCount() == 8 || backend2.requestCount() == 8).isTrue();
    }

    @Test
    @DisplayName("rejects an unknown algorithm with a message listing the valid ones")
    void rejectsUnknownAlgorithm() {
        admin.post().uri("/admin/load-balancer/algorithm")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("algorithm", "MAGIC_ROUTING"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo("INVALID_ALGORITHM")
                .jsonPath("$.message").value(message ->
                        assertThat((String) message).contains("ROUND_ROBIN").contains("CONSISTENT_HASH"));

        // The active algorithm is untouched by a rejected change.
        admin.get().uri("/admin/algorithm")
                .exchange()
                .expectBody().jsonPath("$.algorithm").isEqualTo("ROUND_ROBIN");
    }

    @Test
    @DisplayName("algorithm names are accepted case-insensitively")
    void acceptsLowercaseAlgorithm() {
        admin.post().uri("/admin/load-balancer/algorithm")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("algorithm", "least_connections"))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.currentAlgorithm").isEqualTo("LEAST_CONNECTIONS");
    }

    // ------------------------------------------------------------------
    // Dynamic backend management
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a newly registered backend starts receiving traffic immediately")
    void addsBackendAtRuntime() {
        registerSpare();

        for (int i = 0; i < 9; i++) {
            client.get().uri("/api/test").exchange().expectStatus().isOk();
        }

        assertThat(spare.requestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("rejects a duplicate backend id with 409 instead of overwriting")
    void rejectsDuplicateBackend() {
        admin.post().uri("/admin/backends")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("id", "backend-1", "host", "other", "port", 9999))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.status").isEqualTo("CONFLICT");

        // The live backend still points where it did, so its in-flight requests are unaffected.
        assertThat(registry.find("backend-1").orElseThrow().port()).isEqualTo(backend1.port());
    }

    @Test
    @DisplayName("rejects an invalid backend definition with 400")
    void rejectsInvalidBackend() {
        admin.post().uri("/admin/backends")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("id", "bad", "host", "", "port", 70000))
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(registry.find("bad")).isEmpty();
    }

    @Test
    @DisplayName("disable stops new traffic to a backend")
    void disablesBackend() {
        admin.post().uri("/admin/backends/backend-2/disable")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("DRAINING");

        for (int i = 0; i < 10; i++) {
            client.get().uri("/api/test").exchange().expectStatus().isOk();
        }

        assertThat(backend2.requestCount()).isZero();
        assertThat(backend1.requestCount()).isEqualTo(10);

        admin.post().uri("/admin/backends/backend-2/enable").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("enable returns a backend to service")
    void enablesBackend() {
        admin.post().uri("/admin/backends/backend-2/disable").exchange().expectStatus().isAccepted();
        admin.post().uri("/admin/backends/backend-2/enable").exchange().expectStatus().isOk();
        registry.markHealthy(registry.find("backend-2").orElseThrow(), "test");

        backend1.reset();
        backend2.reset();
        for (int i = 0; i < 10; i++) {
            client.get().uri("/api/test").exchange().expectStatus().isOk();
        }

        assertThat(backend2.requestCount()).isPositive();
    }

    @Test
    @DisplayName("DELETE drains and then removes the backend")
    void removesBackendAfterDraining() {
        registerSpare();

        admin.delete().uri("/admin/backends/spare-backend")
                .exchange()
                // 202, not 204: removal completes once in-flight work finishes.
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("DRAINING");

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> registry.find("spare-backend").isEmpty());

        assertThat(registry.all()).hasSize(2);
    }

    @Test
    @DisplayName("404 for operations on an unknown backend")
    void reportsUnknownBackend() {
        admin.get().uri("/admin/backends/nope").exchange().expectStatus().isNotFound();
        admin.delete().uri("/admin/backends/nope").exchange().expectStatus().isNotFound();
        admin.post().uri("/admin/backends/nope/disable").exchange().expectStatus().isNotFound();
        admin.post().uri("/admin/backends/nope/enable").exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("a weight change immediately affects weight-aware routing")
    void updatesWeightAtRuntime() {
        setAlgorithm("WEIGHTED_ROUND_ROBIN");

        admin.put().uri("/admin/backends/backend-1/weight")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("weight", 3))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.weight").isEqualTo(3);

        backend1.reset();
        backend2.reset();
        for (int i = 0; i < 40; i++) {
            client.get().uri("/api/test").exchange().expectStatus().isOk();
        }

        // 3:1 weights over a 4-slot schedule, so exactly 30/10.
        assertThat(backend1.requestCount()).isEqualTo(30);
        assertThat(backend2.requestCount()).isEqualTo(10);

        admin.put().uri("/admin/backends/backend-1/weight")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("weight", 1))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("rejects a non-positive weight")
    void rejectsInvalidWeight() {
        admin.put().uri("/admin/backends/backend-1/weight")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("weight", 0))
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(registry.find("backend-1").orElseThrow().weight()).isEqualTo(1);
    }

    @Test
    @DisplayName("config reload reports which settings it could not apply")
    void reloadsConfiguration() {
        admin.post().uri("/admin/config/reload")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.reloaded").isEqualTo(true)
                .jsonPath("$.algorithm").exists()
                // Honesty about partial support: an operator who edited a timeout is told it
                // needs a restart rather than being given a misleading "OK".
                .jsonPath("$.unsupported").isNotEmpty()
                .jsonPath("$.message").exists();
    }

    @Test
    @DisplayName("GET /admin/routes lists configured routes")
    void listsRoutes() {
        admin.get().uri("/admin/routes")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("the circuit breaker can be reset by an operator")
    void resetsCircuitBreaker() {
        admin.post().uri("/admin/backends/backend-1/circuit-breaker/reset")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("actuator does not expose env or configprops, which would leak the admin token")
    void doesNotExposeSensitiveActuatorEndpoints() {
        client.get().uri("/actuator/env").exchange().expectStatus().isNotFound();
        client.get().uri("/actuator/configprops").exchange().expectStatus().isNotFound();
        client.get().uri("/actuator/heapdump").exchange().expectStatus().isNotFound();
    }

    private void registerSpare() {
        Map<String, Object> request = new HashMap<>();
        request.put("id", "spare-backend");
        request.put("host", spare.host());
        request.put("port", spare.port());
        request.put("weight", 1);

        admin.post().uri("/admin/backends")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isEqualTo("spare-backend")
                .jsonPath("$.status").isEqualTo("UP");
    }
}
