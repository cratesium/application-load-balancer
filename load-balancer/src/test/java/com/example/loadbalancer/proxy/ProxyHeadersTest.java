package com.example.loadbalancer.proxy;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.config.LoadBalancerProperties;
import com.example.loadbalancer.exception.InvalidRequestException;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import com.example.loadbalancer.testsupport.TestBackends;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Header handling tests.
 *
 * <p>Every case here is a correctness or security property, not formatting: forwarding a
 * hop-by-hop header creates a request-smuggling primitive, and trusting a forwarding header from
 * an arbitrary client hands routing control to the caller.
 */
class ProxyHeadersTest {

    private final BackendServer backend = TestBackends.backend("backend-1");

    private ProxyHeaders headers(boolean preserveHost) {
        LoadBalancerProperties properties = new LoadBalancerProperties(
                new LoadBalancerProperties.Listen("0.0.0.0", 8080),
                LoadBalancingAlgorithm.ROUND_ROBIN,
                List.of(TestBackends.backendConfig("backend-1", "host-1", 8080, 1)),
                List.of(),
                new LoadBalancerProperties.ConsistentHash(100),
                new LoadBalancerProperties.HealthCheck(false, "/health",
                        java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(2),
                        java.time.Duration.ofSeconds(3), java.time.Duration.ZERO, 3, 2,
                        java.util.Set.of(), true),
                new LoadBalancerProperties.PassiveHealth(true, 5, java.time.Duration.ofSeconds(30)),
                new LoadBalancerProperties.Timeouts(java.time.Duration.ofSeconds(2),
                        java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(30),
                        java.time.Duration.ofSeconds(60)),
                new LoadBalancerProperties.ConnectionPool(500, java.time.Duration.ofSeconds(5),
                        java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(5),
                        java.time.Duration.ofMinutes(2)),
                new LoadBalancerProperties.Retry(true, 2, java.util.Set.of("GET"),
                        java.util.Set.of(503), java.time.Duration.ofMillis(1), false,
                        org.springframework.util.unit.DataSize.ofKilobytes(256)),
                new LoadBalancerProperties.CircuitBreaker(true, 20, 10, 50,
                        java.time.Duration.ofSeconds(10), 3, 2),
                new LoadBalancerProperties.Draining(java.time.Duration.ofSeconds(5),
                        java.time.Duration.ofMillis(50)),
                new LoadBalancerProperties.Shutdown(java.time.Duration.ofSeconds(5)),
                new LoadBalancerProperties.Limits(
                        org.springframework.util.unit.DataSize.ofMegabytes(10),
                        org.springframework.util.unit.DataSize.ofKilobytes(16),
                        org.springframework.util.unit.DataSize.ofKilobytes(8), 10_000, 5000),
                new LoadBalancerProperties.Admin(true, "token", "/admin"),
                new LoadBalancerProperties.Proxy(preserveHost, true, List.of(), "X-Request-ID"));
        return new ProxyHeaders(properties);
    }

    @Test
    @DisplayName("forwards ordinary client headers unchanged")
    void forwardsOrdinaryHeaders() {
        ServerHttpRequest request = MockServerHttpRequest.get("http://alb.example.com/api/test")
                .header("Authorization", "Bearer client-token")
                .header("X-Custom", "value")
                .header("Accept", "application/json")
                .build();

        HttpHeaders outbound = headers(false)
                .buildRequestHeaders(request, backend, "req-1", "203.0.113.1", false);

        // Authorization is forwarded to the backend (the backend needs it) but is never logged.
        assertThat(outbound.getFirst("Authorization")).isEqualTo("Bearer client-token");
        assertThat(outbound.getFirst("X-Custom")).isEqualTo("value");
        assertThat(outbound.getFirst("Accept")).isEqualTo("application/json");
    }

    @Test
    @DisplayName("strips every hop-by-hop header")
    void stripsHopByHopHeaders() {
        ServerHttpRequest request = MockServerHttpRequest.get("http://alb.example.com/api/test")
                .header("Connection", "keep-alive")
                .header("Keep-Alive", "timeout=5")
                .header("Transfer-Encoding", "chunked")
                .header("TE", "trailers")
                .header("Trailer", "Expires")
                .header("Upgrade", "websocket")
                .header("Proxy-Authorization", "Basic abc")
                .build();

        HttpHeaders outbound = headers(false)
                .buildRequestHeaders(request, backend, "req-1", "203.0.113.1", false);

        // Transfer-Encoding especially: the ALB re-frames the body, so forwarding the client's
        // framing declaration would produce a message whose declared framing contradicts its
        // actual framing — the basis of request smuggling.
        assertThat(outbound.keySet())
                .extracting(name -> name.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("connection", "keep-alive", "transfer-encoding", "te", "trailer",
                        "upgrade", "proxy-authorization");
    }

    @Test
    @DisplayName("strips headers that the Connection header declares hop-by-hop")
    void stripsConnectionListedHeaders() {
        ServerHttpRequest request = MockServerHttpRequest.get("http://alb.example.com/api/test")
                .header("Connection", "keep-alive, X-Internal-Hint, X-Another")
                .header("X-Internal-Hint", "secret")
                .header("X-Another", "also-secret")
                .header("X-Kept", "fine")
                .build();

        HttpHeaders outbound = headers(false)
                .buildRequestHeaders(request, backend, "req-1", "203.0.113.1", false);

        assertThat(outbound.getFirst("X-Internal-Hint")).isNull();
        assertThat(outbound.getFirst("X-Another")).isNull();
        assertThat(outbound.getFirst("X-Kept")).isEqualTo("fine");
    }

    @Test
    @DisplayName("replaces forwarding headers from an untrusted peer")
    void replacesForwardingHeadersFromUntrustedPeer() {
        ServerHttpRequest request = MockServerHttpRequest.get("http://alb.example.com/api/test")
                .header(HttpHeaders.HOST, "alb.example.com")
                .header("X-Forwarded-For", "1.2.3.4")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "spoofed.example.com")
                .header("Forwarded", "for=1.2.3.4")
                .build();

        HttpHeaders outbound = headers(false)
                .buildRequestHeaders(request, backend, "req-1", "203.0.113.9", false);

        // Only what the ALB observed survives; the client's claims are discarded.
        assertThat(outbound.getFirst("X-Forwarded-For")).isEqualTo("203.0.113.9");
        assertThat(outbound.getFirst("X-Forwarded-Proto")).isEqualTo("http");
        assertThat(outbound.getFirst("Forwarded")).isNull();
        assertThat(outbound.getFirst("X-Forwarded-Host")).isEqualTo("alb.example.com");
    }

    @Test
    @DisplayName("appends the peer address to the chain from a trusted proxy")
    void appendsPeerToChainFromTrustedProxy() {
        ServerHttpRequest request = MockServerHttpRequest.get("http://alb.example.com/api/test")
                .header("X-Forwarded-For", "203.0.113.9, 10.2.2.2")
                .build();

        HttpHeaders outbound = headers(false)
                .buildRequestHeaders(request, backend, "req-1", "10.1.1.1", true);

        // The address appended is the hop we observed, not the resolved original client —
        // appending the latter would duplicate the leftmost entry and describe a hop list
        // that never happened.
        assertThat(outbound.getFirst("X-Forwarded-For")).isEqualTo("203.0.113.9, 10.2.2.2, 10.1.1.1");
    }

    @Test
    @DisplayName("starts the chain when a trusted proxy sent no X-Forwarded-For")
    void startsChainWhenAbsent() {
        ServerHttpRequest request = MockServerHttpRequest.get("http://alb.example.com/api/test").build();

        HttpHeaders outbound = headers(false)
                .buildRequestHeaders(request, backend, "req-1", "10.1.1.1", true);

        assertThat(outbound.getFirst("X-Forwarded-For")).isEqualTo("10.1.1.1");
    }

    @Test
    @DisplayName("rewrites Host to the backend authority by default")
    void rewritesHostByDefault() {
        ServerHttpRequest request = MockServerHttpRequest.get("http://alb.example.com/api/test")
                .header(HttpHeaders.HOST, "alb.example.com")
                .build();

        HttpHeaders outbound = headers(false)
                .buildRequestHeaders(request, backend, "req-1", "203.0.113.1", false);

        assertThat(outbound.getFirst(HttpHeaders.HOST)).isEqualTo(backend.authority());
        // The original is still available to the backend, in the standard place.
        assertThat(outbound.getFirst("X-Forwarded-Host")).isEqualTo("alb.example.com");
    }

    @Test
    @DisplayName("preserves the client Host when configured to")
    void preservesHostWhenConfigured() {
        ServerHttpRequest request = MockServerHttpRequest.get("http://alb.example.com/api/test")
                .header(HttpHeaders.HOST, "alb.example.com")
                .build();

        HttpHeaders outbound = headers(true)
                .buildRequestHeaders(request, backend, "req-1", "203.0.113.1", false);

        assertThat(outbound.getFirst(HttpHeaders.HOST)).isEqualTo("alb.example.com");
    }

    @Test
    @DisplayName("always sets the request id header")
    void setsRequestId() {
        ServerHttpRequest request = MockServerHttpRequest.get("http://alb.example.com/api/test").build();

        HttpHeaders outbound = headers(false)
                .buildRequestHeaders(request, backend, "req-abc-123", "203.0.113.1", false);

        assertThat(outbound.getFirst("X-Request-ID")).isEqualTo("req-abc-123");
    }

    @Test
    @DisplayName("drops Content-Length so framing is re-derived from what is actually sent")
    void dropsContentLength() {
        ServerHttpRequest request = MockServerHttpRequest.post("http://alb.example.com/api/test")
                .header(HttpHeaders.CONTENT_LENGTH, "42")
                .build();

        HttpHeaders outbound = headers(false)
                .buildRequestHeaders(request, backend, "req-1", "203.0.113.1", false);

        assertThat(outbound.getFirst(HttpHeaders.CONTENT_LENGTH)).isNull();
    }

    @Test
    @DisplayName("propagates tracing headers untouched")
    void propagatesTracingHeaders() {
        ServerHttpRequest request = MockServerHttpRequest.get("http://alb.example.com/api/test")
                .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .header("tracestate", "vendor=value")
                .header("baggage", "userId=alice")
                .build();

        HttpHeaders outbound = headers(false)
                .buildRequestHeaders(request, backend, "req-1", "203.0.113.1", false);

        assertThat(outbound.getFirst("traceparent"))
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(outbound.getFirst("tracestate")).isEqualTo("vendor=value");
        assertThat(outbound.getFirst("baggage")).isEqualTo("userId=alice");
    }

    @Test
    @DisplayName("copies response headers but not hop-by-hop ones")
    void copiesResponseHeaders() {
        HttpHeaders backendResponse = new HttpHeaders();
        backendResponse.set("Content-Type", "application/json");
        backendResponse.set("ETag", "\"abc\"");
        backendResponse.set("Connection", "close");
        backendResponse.set("Transfer-Encoding", "chunked");
        HttpHeaders clientResponse = new HttpHeaders();

        headers(false).copyResponseHeaders(backendResponse, clientResponse);

        assertThat(clientResponse.getFirst("Content-Type")).isEqualTo("application/json");
        assertThat(clientResponse.getFirst("ETag")).isEqualTo("\"abc\"");
        // 'Connection: close' is a decision about the backend's socket, not the client's.
        assertThat(clientResponse.getFirst("Connection")).isNull();
        assertThat(clientResponse.getFirst("Transfer-Encoding")).isNull();
    }

    @Test
    @DisplayName("rejects a request declaring both Content-Length and Transfer-Encoding")
    void rejectsAmbiguousFraming() {
        ServerHttpRequest request = MockServerHttpRequest.post("http://alb.example.com/api/test")
                .header(HttpHeaders.CONTENT_LENGTH, "10")
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                .build();

        // The classic smuggling setup: proxy and backend disagree about where the request ends,
        // so body bytes become a second attacker-authored request.
        assertThatThrownBy(() -> headers(false).validateRequestFraming(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("both Content-Length and Transfer-Encoding");
    }

    @Test
    @DisplayName("rejects duplicate Content-Length values")
    void rejectsDuplicateContentLength() {
        ServerHttpRequest request = MockServerHttpRequest.post("http://alb.example.com/api/test")
                .header(HttpHeaders.CONTENT_LENGTH, "10", "20")
                .build();

        assertThatThrownBy(() -> headers(false).validateRequestFraming(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("multiple Content-Length");
    }

    @Test
    @DisplayName("accepts an ordinary well-formed request")
    void acceptsWellFormedRequest() {
        ServerHttpRequest request = MockServerHttpRequest.post("http://alb.example.com/api/test")
                .header(HttpHeaders.CONTENT_LENGTH, "10")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();

        assertThatCode(() -> headers(false).validateRequestFraming(request)).doesNotThrowAnyException();
    }
}
