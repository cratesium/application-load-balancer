package com.example.loadbalancer.config;

import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Root of the load balancer's configuration tree.
 *
 * <p>Every tunable lives here — there are no hardcoded hosts, ports, timeouts or
 * thresholds anywhere in the codebase. The type is a deeply nested {@code record}
 * so that the bound configuration is immutable: runtime mutation (adding a backend,
 * switching the algorithm) happens in the registries, never by mutating this object.
 * That separation keeps "what the operator configured" distinct from "what the system
 * is currently doing", which matters when {@code POST /admin/config/reload} re-reads
 * the file and has to diff the two.
 *
 * <p>Validation is applied at startup via {@link Validated}; a violation aborts the
 * boot with a readable message rather than surfacing as a {@code NullPointerException}
 * on the first request.
 */
@Validated
@ConfigurationProperties(prefix = "load-balancer")
public record LoadBalancerProperties(

        @Valid @NotNull @DefaultValue Listen listen,

        /** Algorithm used when a route does not override it. */
        @NotNull @DefaultValue("ROUND_ROBIN") LoadBalancingAlgorithm algorithm,

        @Valid @NotEmpty List<Backend> backends,

        @Valid @DefaultValue List<Route> routes,

        @Valid @NotNull @DefaultValue ConsistentHash consistentHash,

        @Valid @NotNull @DefaultValue HealthCheck healthCheck,

        @Valid @NotNull @DefaultValue PassiveHealth passiveHealth,

        @Valid @NotNull @DefaultValue Timeouts timeouts,

        @Valid @NotNull @DefaultValue ConnectionPool connectionPool,

        @Valid @NotNull @DefaultValue Retry retry,

        @Valid @NotNull @DefaultValue CircuitBreaker circuitBreaker,

        @Valid @NotNull @DefaultValue Draining draining,

        @Valid @NotNull @DefaultValue Shutdown shutdown,

        @Valid @NotNull @DefaultValue Limits limits,

        @Valid @NotNull @DefaultValue Admin admin,

        @Valid @NotNull @DefaultValue Proxy proxy) {

    /**
     * The socket the ALB listens on. Authoritative over {@code server.port}.
     *
     * @param port the listen port. 0 means "let the OS assign an ephemeral port", which is what
     *             integration tests use so several can run in parallel without colliding, and
     *             what a sidecar deployment uses when its port is discovered rather than fixed.
     */
    public record Listen(
            @NotBlank @DefaultValue("0.0.0.0") String host,
            @Min(0) @Max(65535) @DefaultValue("8080") int port) {
    }

    /**
     * A single backend server. {@code id} is the stable identity used by metrics,
     * routes and admin APIs, so it must be unique and should not encode the address
     * (an address can change; the identity should not).
     */
    public record Backend(
            @NotBlank String id,
            @NotBlank String host,
            @Min(1) @Max(65535) int port,
            @DefaultValue("false") boolean secure,
            @Positive @DefaultValue("1") int weight,
            @DefaultValue("true") boolean enabled) {
    }

    /**
     * An optional routing rule. Rules are evaluated in declaration order and the first
     * match wins; if nothing matches, the global backend pool is used.
     */
    public record Route(
            String id,
            @NotBlank String path,
            @DefaultValue Set<String> methods,
            @DefaultValue List<String> backends,
            /** Optional per-route algorithm override; falls back to the global algorithm. */
            LoadBalancingAlgorithm algorithm) {
    }

    public record ConsistentHash(
            @Min(1) @Max(10_000) @DefaultValue("100") int virtualNodes) {
    }

    /**
     * Active health checking. {@code failureThreshold} consecutive failures move a
     * backend to DOWN; {@code successThreshold} consecutive successes bring it back.
     * Requiring more than one success prevents a single lucky probe from re-admitting
     * a flapping backend.
     */
    public record HealthCheck(
            @DefaultValue("true") boolean enabled,
            @NotBlank @DefaultValue("/health") String path,
            @NotNull @DefaultValue("5s") Duration interval,
            @NotNull @DefaultValue("2s") Duration connectTimeout,
            @NotNull @DefaultValue("3s") Duration responseTimeout,
            @NotNull @DefaultValue("0s") Duration initialDelay,
            @Min(1) @DefaultValue("3") int failureThreshold,
            @Min(1) @DefaultValue("2") int successThreshold,
            /** HTTP statuses treated as healthy. Empty means "any 2xx". */
            @DefaultValue Set<Integer> healthyStatuses,
            /**
             * Whether a newly registered backend starts UP (optimistic) or DOWN until it
             * passes {@code successThreshold} probes. Optimistic avoids a cold-start window
             * where the ALB has no routable backends; strict is safer for rolling deploys
             * into a pool that is already serving traffic.
             */
            @DefaultValue("true") boolean assumeHealthyOnStart) {
    }

    /**
     * Passive health checking: real proxied traffic is evidence too. A backend that
     * fails {@code failureThreshold} real requests inside {@code window} is marked DOWN
     * without waiting for the next active probe.
     */
    public record PassiveHealth(
            @DefaultValue("true") boolean enabled,
            @Min(1) @DefaultValue("5") int failureThreshold,
            @NotNull @DefaultValue("30s") Duration window) {
    }

    /**
     * Timeout budget. These are distinct failures with distinct causes, so they are
     * configured and reported separately rather than collapsed into one number.
     *
     * @param connection TCP connect timeout
     * @param response   time to first response byte from the backend
     * @param request    end-to-end budget for one client request including retries
     * @param idle       idle timeout applied to pooled backend connections
     */
    public record Timeouts(
            @NotNull @DefaultValue("2s") Duration connection,
            @NotNull @DefaultValue("10s") Duration response,
            @NotNull @DefaultValue("30s") Duration request,
            @NotNull @DefaultValue("60s") Duration idle) {
    }

    /**
     * Backend connection pool sizing. Reactor Netty keys its pools by remote address,
     * so these limits apply <em>per backend</em>, not across the whole ALB.
     */
    public record ConnectionPool(
            @Min(1) @DefaultValue("500") int maxConnections,
            @NotNull @DefaultValue("5s") Duration pendingAcquireTimeout,
            @NotNull @DefaultValue("30s") Duration maxIdleTime,
            @NotNull @DefaultValue("5m") Duration maxLifeTime,
            @NotNull @DefaultValue("2m") Duration evictInBackground) {
    }

    /**
     * Retry policy. Defaults are deliberately conservative: only idempotent methods,
     * only transport-level failures and 502/503/504, and one extra attempt.
     */
    public record Retry(
            @DefaultValue("true") boolean enabled,
            @Min(1) @DefaultValue("2") int maxAttempts,
            @DefaultValue({"GET", "HEAD", "OPTIONS"}) Set<String> methods,
            @DefaultValue({"502", "503", "504"}) Set<Integer> retryableStatuses,
            @NotNull @DefaultValue("20ms") Duration backoff,
            /**
             * Retrying a request with a body means replaying that body, which means
             * buffering it. Off by default: a streaming proxy should not silently start
             * holding request bodies in memory.
             */
            @DefaultValue("false") boolean bufferRequestBody,
            @NotNull @DefaultValue("256KB") DataSize maxBufferedBody) {
    }

    public record CircuitBreaker(
            @DefaultValue("true") boolean enabled,
            @Min(1) @DefaultValue("20") int slidingWindowSize,
            @Min(1) @DefaultValue("10") int minimumCalls,
            @Min(1) @Max(100) @DefaultValue("50") int failureRateThreshold,
            @NotNull @DefaultValue("10s") Duration openDuration,
            @Min(1) @DefaultValue("3") int halfOpenMaxCalls,
            @Min(1) @DefaultValue("2") int halfOpenSuccessesToClose) {
    }

    public record Draining(
            @NotNull @DefaultValue("30s") Duration timeout,
            @NotNull @DefaultValue("1s") Duration checkInterval) {
    }

    public record Shutdown(
            @NotNull @DefaultValue("30s") Duration gracePeriod) {
    }

    /**
     * Resource limits. Without these the ALB is a memory-amplification device: every
     * client that can open a connection can make it buffer.
     */
    public record Limits(
            @NotNull @DefaultValue("10MB") DataSize maxRequestBody,
            @NotNull @DefaultValue("16KB") DataSize maxHeaderSize,
            @NotNull @DefaultValue("8KB") DataSize maxInitialLineLength,
            /** In-flight client requests admitted before the ALB sheds load with a 503. */
            @Min(1) @DefaultValue("10000") int maxConcurrentRequests,
            /**
             * How many admitted requests may queue waiting for a backend connection.
             * Applied as the pending-acquire queue bound of each backend's connection pool;
             * exceeding it fails that request rather than growing the queue without limit.
             */
            @Min(1) @DefaultValue("5000") int maxPendingRequests) {
    }

    public record Admin(
            @DefaultValue("true") boolean enabled,
            /** Bearer token for /admin/**. Injected from the environment, never literal. */
            String token,
            @NotBlank @DefaultValue("/admin") String pathPrefix) {
    }

    /**
     * Proxy behaviour.
     *
     * @param trustedProxies CIDRs whose {@code X-Forwarded-For} we are willing to
     *                       believe. Empty means "trust nothing" — the TCP peer address
     *                       is used as the client IP, which is the safe default.
     */
    public record Proxy(
            @DefaultValue("false") boolean preserveHostHeader,
            @DefaultValue("true") boolean addForwardedHeaders,
            @DefaultValue List<String> trustedProxies,
            @NotBlank @DefaultValue("X-Request-ID") String requestIdHeader) {
    }
}
