package com.example.loadbalancer.integration;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests: a real ALB on a real port, forwarding to real HTTP servers.
 *
 * <p>The stub backends are started before the Spring context so their ephemeral ports can be
 * injected as configuration — which also exercises the requirement that backends come from
 * configuration and are never hardcoded.
 *
 * <p>Health checks are disabled in most of these tests so that routing assertions are not racing
 * a background prober. {@code HealthCheckIntegrationTest} covers the prober specifically.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoadBalancerIntegrationTest {

    private static StubBackend backend1;
    private static StubBackend backend2;
    private static StubBackend backend3;

    /** Dedicated pool per test — see {@code TestClients} for why the injected client is not used. */
    private WebTestClient client;

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int albPort;

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
    static void configureBackends(DynamicPropertyRegistry registry) {
        registry.add("load-balancer.backends[0].id", () -> "backend-1");
        registry.add("load-balancer.backends[0].host", backend1::host);
        registry.add("load-balancer.backends[0].port", backend1::port);
        registry.add("load-balancer.backends[1].id", () -> "backend-2");
        registry.add("load-balancer.backends[1].host", backend2::host);
        registry.add("load-balancer.backends[1].port", backend2::port);
        registry.add("load-balancer.backends[2].id", () -> "backend-3");
        registry.add("load-balancer.backends[2].host", backend3::host);
        registry.add("load-balancer.backends[2].port", backend3::port);

        registry.add("load-balancer.algorithm", () -> "ROUND_ROBIN");
        registry.add("load-balancer.health-check.enabled", () -> "false");
        registry.add("load-balancer.admin.token", () -> "integration-test-token");
        registry.add("load-balancer.retry.enabled", () -> "true");
        registry.add("load-balancer.timeouts.response", () -> "2s");
        registry.add("load-balancer.timeouts.request", () -> "5s");
        // 0 = ephemeral port. load-balancer.listen.port is authoritative over server.port, so
        // without this the ALB would ignore RANDOM_PORT and try to bind 8080.
        registry.add("load-balancer.listen.port", () -> "0");
        registry.add("spring.test.webtestclient.timeout", () -> "30s");
        // The Prometheus scrape grows past WebTestClient's default 256KB in-memory limit once
        // latency histograms exist across several tag combinations. Worth noting for real
        // deployments too: percentile histograms are the bulk of a scrape payload.
        registry.add("spring.codec.max-in-memory-size", () -> "10MB");
    }

    @BeforeEach
    void resetBackends() {
        client = com.example.loadbalancer.testsupport.TestClients.forPort(albPort, "proxy-test");
        backend1.reset();
        backend2.reset();
        backend3.reset();
    }

    @Test
    @DisplayName("forwards a GET and returns the backend's response to the client")
    void forwardsGetRequests() {
        client.get().uri("/api/test")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Request-ID")
                .expectBody()
                .jsonPath("$.path").isEqualTo("/api/test");

        assertThat(totalReceived()).isEqualTo(1);
    }

    @Test
    @DisplayName("round-robins across all three backends")
    void distributesAcrossBackends() {
        for (int i = 0; i < 9; i++) {
            client.get().uri("/api/test").exchange().expectStatus().isOk();
        }

        // Three backends, nine requests, even rotation.
        assertThat(backend1.requestCount()).isEqualTo(3);
        assertThat(backend2.requestCount()).isEqualTo(3);
        assertThat(backend3.requestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("preserves the path and query string byte for byte")
    void preservesPathAndQuery() {
        // URI, not String: WebTestClient.uri(String) treats its argument as a URI template and
        // would percent-encode the '%' itself, so the assertion would be testing the test
        // client rather than the proxy.
        client.get().uri(rawUri("/api/orders/123/items?status=open&q=a%20b&empty="))
                .exchange()
                .expectStatus().isOk();

        StubBackend.ReceivedRequest received = onlyRequest();
        // Not re-encoded and not normalised: %20 stays %20, and the empty parameter survives.
        assertThat(received.uri()).isEqualTo("/api/orders/123/items?status=open&q=a%20b&empty=");
    }

    @Test
    @DisplayName("preserves an encoded slash in a path segment")
    void preservesEncodedSlash() {
        client.get().uri(rawUri("/api/files/a%2Fb"))
                .exchange()
                .expectStatus().isOk();

        // Decoding and re-encoding would turn %2F into a real path separator and change which
        // resource the backend serves — and would break backends whose authorisation rules are
        // written against the literal path.
        assertThat(onlyRequest().uri()).isEqualTo("/api/files/a%2Fb");
    }

    @Test
    @DisplayName("forwards the method, body and content type of a POST")
    void forwardsPostBody() {
        client.post().uri("/api/orders?id=123")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"amount\":100}")
                .exchange()
                .expectStatus().isOk();

        StubBackend.ReceivedRequest received = onlyRequest();
        assertThat(received.method()).isEqualTo("POST");
        assertThat(received.uri()).isEqualTo("/api/orders?id=123");
        assertThat(received.body()).isEqualTo("{\"amount\":100}");
        assertThat(received.header("content-type")).contains("application/json");
    }

    @Test
    @DisplayName("forwards PUT and DELETE")
    void forwardsOtherMethods() {
        client.put().uri("/api/orders/123").bodyValue("{}").exchange().expectStatus().isOk();
        client.delete().uri("/api/orders/123").exchange().expectStatus().isOk();

        List<String> methods = allRequests().stream().map(StubBackend.ReceivedRequest::method).toList();
        assertThat(methods).containsExactlyInAnyOrder("PUT", "DELETE");
    }

    @Test
    @DisplayName("forwards arbitrary client headers unchanged")
    void forwardsClientHeaders() {
        client.get().uri("/api/test")
                .header("X-Custom-Header", "custom-value")
                .header("Accept", "application/json")
                .exchange()
                .expectStatus().isOk();

        StubBackend.ReceivedRequest received = onlyRequest();
        assertThat(received.header("x-custom-header")).isEqualTo("custom-value");
        assertThat(received.header("accept")).isEqualTo("application/json");
    }

    @Test
    @DisplayName("adds the standard X-Forwarded-* headers and rewrites Host")
    void addsProxyHeaders() {
        client.get().uri("/api/test").exchange().expectStatus().isOk();

        StubBackend.ReceivedRequest received = onlyRequest();
        assertThat(received.header("x-forwarded-for")).isNotBlank();
        assertThat(received.header("x-forwarded-proto")).isEqualTo("http");
        assertThat(received.header("x-forwarded-host")).isNotBlank();
        assertThat(received.header("x-request-id")).isNotBlank();
        // Host is the backend's own authority by default, so its redirects and absolute URIs
        // are self-consistent.
        assertThat(received.header("host")).contains(String.valueOf(portOf(received)));
    }

    @Test
    @DisplayName("does not forward hop-by-hop headers")
    void stripsHopByHopHeaders() {
        client.get().uri("/api/test")
                .header("Connection", "keep-alive, X-Internal-Hint")
                .header("X-Internal-Hint", "should-be-stripped")
                .header("TE", "trailers")
                .exchange()
                .expectStatus().isOk();

        StubBackend.ReceivedRequest received = onlyRequest();
        assertThat(received.header("te")).isNull();
        // Headers named by Connection are hop-by-hop by declaration and must be dropped too.
        assertThat(received.header("x-internal-hint")).isNull();
    }

    @Test
    @DisplayName("discards X-Forwarded-For from an untrusted peer instead of appending to it")
    void discardsSpoofedForwardedFor() {
        client.get().uri("/api/test")
                .header("X-Forwarded-For", "1.2.3.4")
                .exchange()
                .expectStatus().isOk();

        // No trusted proxies are configured, so the client's claim is replaced with what the
        // ALB actually observed. Otherwise IP_HASH routing would be caller-controlled.
        assertThat(onlyRequest().header("x-forwarded-for")).doesNotContain("1.2.3.4");
    }

    @Test
    @DisplayName("reuses an inbound request id so a trace spans services")
    void reusesInboundRequestId() {
        client.get().uri("/api/test")
                .header("X-Request-ID", "client-supplied-id-123")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Request-ID", "client-supplied-id-123");

        assertThat(onlyRequest().header("x-request-id")).isEqualTo("client-supplied-id-123");
    }

    @Test
    @DisplayName("replaces an unsafe inbound request id rather than echoing it")
    void sanitisesInboundRequestId() {
        client.get().uri("/api/test")
                .header("X-Request-ID", "bad id with spaces & symbols")
                .exchange()
                .expectStatus().isOk();

        // The id is echoed into a response header and into logs, so an unvalidated value would
        // be a header-injection and log-forging vector.
        assertThat(onlyRequest().header("x-request-id")).isNotEqualTo("bad id with spaces & symbols");
    }

    @Test
    @DisplayName("propagates W3C tracing headers untouched")
    void propagatesTracingHeaders() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        client.get().uri("/api/test")
                .header("traceparent", traceparent)
                .header("tracestate", "vendor=value")
                .exchange()
                .expectStatus().isOk();

        StubBackend.ReceivedRequest received = onlyRequest();
        assertThat(received.header("traceparent")).isEqualTo(traceparent);
        assertThat(received.header("tracestate")).isEqualTo("vendor=value");
    }

    @Test
    @DisplayName("relays the backend's status and response headers")
    void relaysBackendResponse() {
        backend1.failNextRequests(1, 404);
        backend2.failNextRequests(1, 404);
        backend3.failNextRequests(1, 404);

        client.get().uri("/api/missing")
                .exchange()
                // 404 is the backend's considered answer and is relayed as-is, not retried.
                .expectStatus().isNotFound()
                .expectHeader().exists("X-Served-By");

        assertThat(totalReceived()).isEqualTo(1);
    }

    @Test
    @DisplayName("retries a 503 on another backend and returns the successful response")
    void retriesRetryableStatus() {
        // Every backend will fail its first request, so whichever is chosen first fails and
        // the retry lands on a different one.
        backend1.failNextRequests(1, 503);

        client.get().uri("/api/test").exchange().expectStatus().isOk();

        // Two attempts total: the failed one and the successful retry.
        assertThat(totalReceived()).isEqualTo(2);
    }

    @Test
    @DisplayName("does not retry a POST by default")
    void doesNotRetryPost() {
        for (int i = 0; i < 3; i++) {
            backendFor(i).failNextRequests(1, 503);
        }

        client.post().uri("/api/orders")
                .bodyValue("{\"amount\":100}")
                .exchange()
                // The 503 is relayed rather than retried: a POST may already have been processed.
                .expectStatus().isEqualTo(503);

        assertThat(totalReceived()).isEqualTo(1);
    }

    @Test
    @DisplayName("returns 504 when a backend does not respond in time")
    void returnsGatewayTimeout() {
        backend1.responseDelay(Duration.ofSeconds(10));
        backend2.responseDelay(Duration.ofSeconds(10));
        backend3.responseDelay(Duration.ofSeconds(10));
        try {
            client.get().uri("/api/slow")
                    .exchange()
                    .expectStatus().isEqualTo(504)
                    .expectBody()
                    .jsonPath("$.error").isEqualTo("GATEWAY_TIMEOUT")
                    .jsonPath("$.requestId").exists();
        } finally {
            backend1.responseDelay(Duration.ZERO);
            backend2.responseDelay(Duration.ZERO);
            backend3.responseDelay(Duration.ZERO);
        }
    }

    @Test
    @DisplayName("error responses never contain a stack trace or backend identity")
    void errorResponsesAreOpaque() {
        backend1.responseDelay(Duration.ofSeconds(10));
        backend2.responseDelay(Duration.ofSeconds(10));
        backend3.responseDelay(Duration.ofSeconds(10));
        try {
            byte[] body = client.get().uri("/api/slow")
                    .exchange()
                    .expectStatus().isEqualTo(504)
                    .expectBody().returnResult().getResponseBodyContent();

            String json = new String(body == null ? new byte[0] : body);
            assertThat(json).doesNotContain("at com.example");
            assertThat(json).doesNotContain("Exception");
            assertThat(json).doesNotContain("backend-1");
            assertThat(json).doesNotContain("127.0.0.1");
        } finally {
            backend1.responseDelay(Duration.ZERO);
            backend2.responseDelay(Duration.ZERO);
            backend3.responseDelay(Duration.ZERO);
        }
    }

    @Test
    @DisplayName("rejects a request body over the configured limit with 413")
    void rejectsOversizedBody() {
        byte[] tooLarge = new byte[11 * 1024 * 1024];

        client.post().uri("/api/upload")
                .bodyValue(tooLarge)
                .exchange()
                .expectStatus().isEqualTo(413);

        // Rejected by the ALB, so no backend ever saw the payload.
        assertThat(totalReceived()).isZero();
    }

    @Test
    @DisplayName("a client cannot make the ALB fetch an arbitrary host")
    void cannotBeUsedForSsrf() {
        // Whatever the path looks like, the authority always comes from the registry. There is
        // no code path from request data to the target host.
        client.get().uri(rawUri("/http://evil.example.com/")).exchange()
                .expectStatus().isOk();
        client.get().uri(rawUri("/redirect?url=http://evil.example.com")).exchange()
                .expectStatus().isOk();

        assertThat(totalReceived()).isEqualTo(2);
        assertThat(allRequests()).allSatisfy(request ->
                assertThat(request.header("host")).contains("127.0.0.1"));
    }

    @Test
    @DisplayName("a protocol-relative target cannot redirect the ALB to another host")
    void protocolRelativeTargetStaysOnTheBackend() {
        // WebTestClient cannot express "//evil.example.com/admin" as a path — it resolves it as
        // a protocol-relative URL and dials that host itself. So this one goes over a raw socket
        // to make the ALB parse the literal request line.
        reactor.netty.http.client.HttpClient.create()
                .baseUrl("http://127.0.0.1:" + albPort)
                .get()
                .uri("//evil.example.com/admin")
                .response()
                .block(Duration.ofSeconds(10));

        // The request reached a configured backend, not evil.example.com.
        assertThat(totalReceived()).isEqualTo(1);
        assertThat(onlyRequest().header("host")).contains("127.0.0.1");
    }

    @Test
    @DisplayName("forwards /health to backends rather than answering it locally")
    void forwardsHealthPath() {
        client.get().uri("/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("the ALB's own health lives under the actuator prefix")
    void exposesOwnHealth() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    @DisplayName("exposes Prometheus metrics with the documented names")
    void exposesPrometheusMetrics() {
        client.get().uri("/api/test").exchange().expectStatus().isOk();

        byte[] body = client.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBodyContent();

        String metrics = new String(body == null ? new byte[0] : body);
        assertThat(metrics).contains("loadbalancer_requests_total");
        assertThat(metrics).contains("loadbalancer_request_duration_seconds");
        assertThat(metrics).contains("loadbalancer_backend_requests_total");
        assertThat(metrics).contains("loadbalancer_backend_active_connections");
        assertThat(metrics).contains("loadbalancer_backend_health_status");
        // Tagged by route id, never by raw path: /api/users/12345 must not create a series.
        assertThat(metrics).contains("route=\"default\"");
    }

    @Test
    @DisplayName("handles 500 concurrent requests without error or counter drift")
    void handlesConcurrentRequests() throws Exception {
        int threads = 50;
        int perThread = 10;
        int requests = threads * perThread;
        // Real threads, not flatMap: WebTestClient.exchange() blocks, so a reactive fan-out
        // would run these sequentially on the subscribing thread and prove nothing.
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        java.util.List<Integer> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();

        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                int threadId = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            statuses.add(client.get().uri("/api/test?t=" + threadId + "&i=" + i)
                                    .exchange()
                                    .returnResult(Void.class)
                                    .getStatus()
                                    .value());
                        }
                    } catch (Throwable error) {
                        failures.add(error);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(120, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }

        assertThat(failures).isEmpty();
        assertThat(statuses).hasSize(requests).allMatch(status -> status == 200);
        assertThat(totalReceived()).isEqualTo(requests);
        // Backends saw genuinely simultaneous work, so the counters were exercised concurrently.
        assertThat(backend1.maxObservedInFlight() + backend2.maxObservedInFlight()
                + backend3.maxObservedInFlight()).isGreaterThan(1);

        // Every request released its slot: a leaked increment would leave this non-zero and
        // would permanently distort least-connections routing.
        client.get().uri("/admin/status")
                .header("Authorization", "Bearer integration-test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.activeRequests").isEqualTo(0);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Builds an absolute URI against the ALB so the value is sent verbatim.
     *
     * <p>{@code uri(String)} would treat the argument as a URI template and encode the '%';
     * a <em>relative</em> {@code URI} makes WebTestClient ignore its base URL and dial port 80.
     * Absolute-and-pre-encoded is the only form that tests the proxy rather than the client.
     */
    private java.net.URI rawUri(String rawPathAndQuery) {
        return java.net.URI.create("http://127.0.0.1:" + albPort + rawPathAndQuery);
    }

    private StubBackend backendFor(int index) {
        return switch (index) {
            case 0 -> backend1;
            case 1 -> backend2;
            default -> backend3;
        };
    }

    private int totalReceived() {
        return backend1.requestCount() + backend2.requestCount() + backend3.requestCount();
    }

    private List<StubBackend.ReceivedRequest> allRequests() {
        List<StubBackend.ReceivedRequest> all = new java.util.ArrayList<>();
        all.addAll(backend1.receivedRequests());
        all.addAll(backend2.receivedRequests());
        all.addAll(backend3.receivedRequests());
        return all;
    }

    private StubBackend.ReceivedRequest onlyRequest() {
        List<StubBackend.ReceivedRequest> all = allRequests();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    /** @return the port of the backend that received the given request. */
    private int portOf(StubBackend.ReceivedRequest request) {
        Map<StubBackend, Integer> ports = new HashMap<>();
        ports.put(backend1, backend1.port());
        ports.put(backend2, backend2.port());
        ports.put(backend3, backend3.port());
        for (Map.Entry<StubBackend, Integer> entry : ports.entrySet()) {
            if (entry.getKey().receivedRequests().contains(request)) {
                return entry.getValue();
            }
        }
        throw new AssertionError("request was not received by any stub backend");
    }
}
