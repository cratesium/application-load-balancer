package com.example.demobackend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Endpoints for demonstrating and testing load balancer behaviour.
 *
 * <p>Nothing here is production code — it is instrumentation. The state it keeps (request
 * counts, a health toggle) exists so that a person running the demo can see which backend
 * served which request and can induce failures on demand.
 */
@RestController
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final String serverName;
    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final Instant startedAt = Instant.now();

    public DemoController(@Value("${demo.server-name:backend}") String serverName) {
        this.serverName = serverName;
    }

    /** Liveness/readiness probe target for the ALB's active health checks. */
    @GetMapping("/health")
    public Mono<ResponseEntity<Map<String, Object>>> health() {
        boolean up = healthy.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("server", serverName);
        body.put("status", up ? "UP" : "DOWN");
        return Mono.just(ResponseEntity
                .status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body));
    }

    /** The endpoint the distribution demos hit in a loop. */
    @GetMapping("/api/test")
    public Mono<Map<String, Object>> test(@RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return Mono.just(describe(requestId, "Request processed by " + serverName));
    }

    /**
     * Holds the request open for {@code ms} milliseconds without blocking a thread.
     *
     * <p>This is what makes least-connections observable: point traffic at a slow backend
     * and a fast one and watch the in-flight count on the slow one stop it from attracting
     * new requests.
     */
    @GetMapping("/api/slow")
    public Mono<Map<String, Object>> slow(@RequestParam(defaultValue = "1000") long ms,
                                          @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        long clamped = Math.max(0, Math.min(ms, 60_000));
        return Mono.delay(Duration.ofMillis(clamped))
                .map(ignored -> describe(requestId, "Slow response after " + clamped + "ms from " + serverName))
                .doFirst(inFlight::incrementAndGet)
                .doFinally(signal -> inFlight.decrementAndGet());
    }

    /** Always fails, for exercising retries, passive health checks and the circuit breaker. */
    @GetMapping("/api/fail")
    public Mono<ResponseEntity<Map<String, Object>>> fail(
            @RequestParam(defaultValue = "503") int status,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        Map<String, Object> body = describe(requestId, "Deliberate failure from " + serverName);
        return Mono.just(ResponseEntity.status(HttpStatus.valueOf(status)).body(body));
    }

    /** Echoes method, query, headers and body so proxy fidelity can be verified end to end. */
    @PostMapping("/api/echo")
    public Mono<Map<String, Object>> echoPost(@RequestBody(required = false) String body,
                                              @RequestParam Map<String, String> query,
                                              @RequestHeader Map<String, String> headers) {
        return Mono.just(echo("POST", body, query, headers));
    }

    @PutMapping("/api/echo")
    public Mono<Map<String, Object>> echoPut(@RequestBody(required = false) String body,
                                             @RequestParam Map<String, String> query,
                                             @RequestHeader Map<String, String> headers) {
        return Mono.just(echo("PUT", body, query, headers));
    }

    @GetMapping("/api/echo")
    public Mono<Map<String, Object>> echoGet(@RequestParam Map<String, String> query,
                                             @RequestHeader Map<String, String> headers) {
        return Mono.just(echo("GET", null, query, headers));
    }

    @DeleteMapping("/api/echo")
    public Mono<Map<String, Object>> echoDelete(@RequestParam Map<String, String> query,
                                                @RequestHeader Map<String, String> headers) {
        return Mono.just(echo("DELETE", null, query, headers));
    }

    /** Path-variable route, to prove arbitrary paths are forwarded verbatim. */
    @GetMapping("/api/users/{id}")
    public Mono<Map<String, Object>> user(@PathVariable String id) {
        Map<String, Object> body = describe(null, "User lookup on " + serverName);
        body.put("userId", id);
        return Mono.just(body);
    }

    /** Flips this backend's health so active health-check transitions can be demonstrated. */
    @PostMapping("/admin/health/{state}")
    public Mono<Map<String, Object>> setHealth(@PathVariable String state) {
        boolean up = !"down".equalsIgnoreCase(state);
        healthy.set(up);
        log.warn("Demo backend {} health flipped to {}", serverName, up ? "UP" : "DOWN");
        return Mono.just(Map.of("server", serverName, "health", up ? "UP" : "DOWN"));
    }

    /** Per-backend counters, used by the demo scripts to print an observed distribution. */
    @GetMapping("/stats")
    public Mono<Map<String, Object>> stats() {
        return Mono.just(Map.of(
                "server", serverName,
                "totalRequests", requestCount.get(),
                "inFlight", inFlight.get(),
                "healthy", healthy.get(),
                "uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds()));
    }

    private Map<String, Object> describe(String requestId, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("server", serverName);
        body.put("message", message);
        body.put("requestNumber", requestCount.incrementAndGet());
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return body;
    }

    private Map<String, Object> echo(String method, String body, Map<String, String> query,
                                     Map<String, String> headers) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("server", serverName);
        response.put("method", method);
        response.put("query", query);
        response.put("headers", headers);
        response.put("body", body);
        requestCount.incrementAndGet();
        return response;
    }
}
