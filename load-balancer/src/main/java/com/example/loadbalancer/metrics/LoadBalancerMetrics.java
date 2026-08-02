package com.example.loadbalancer.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Request-level metrics for the load balancer as a whole.
 *
 * <h2>Cardinality discipline</h2>
 * Every tag value here comes from a bounded set: the HTTP method (normalised, with anything
 * unrecognised collapsed to {@code OTHER}), the numeric status, the algorithm name, and the
 * <em>route id</em> — a configured identifier, never the request path. Tagging by raw path
 * is the classic way to destroy a Prometheus server: {@code /api/users/12345} creates one
 * time series per user id, and a few million users means a few million series, each with its
 * own histogram buckets. Route ids are configuration, so their count is known in advance.
 *
 * <p>Backend id is a tag on backend-scoped meters only. It is bounded by the size of the
 * pool, and the metrics for a removed backend are unregistered with it.
 */
@Component
public class LoadBalancerMetrics {

    /** Methods that get their own tag value; everything else is collapsed to OTHER. */
    private static final Set<String> KNOWN_METHODS =
            Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE");

    private final MeterRegistry registry;

    private final AtomicInteger activeRequests = new AtomicInteger();
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong failedRequests = new AtomicLong();
    private final AtomicLong noHealthyBackendRejections = new AtomicLong();

    public LoadBalancerMetrics(MeterRegistry registry) {
        this.registry = registry;

        registry.gauge("loadbalancer_active_requests", activeRequests, AtomicInteger::get);
        registry.gauge("loadbalancer_requests_total_value", totalRequests, AtomicLong::get);
    }

    /**
     * Records a completed client request.
     *
     * @param algorithm  algorithm that selected the backend, or {@code none} if selection failed
     * @param method     HTTP method
     * @param status     status returned to the client
     * @param route      matched route id
     * @param durationNs wall-clock duration including any retries
     */
    public void recordRequest(String algorithm, String method, int status, String route, long durationNs) {
        totalRequests.incrementAndGet();
        Counter.builder("loadbalancer_requests_total")
                .description("Client requests handled by the load balancer")
                .tag("algorithm", algorithm)
                .tag("method", normaliseMethod(method))
                .tag("status", String.valueOf(status))
                .tag("route", route)
                .register(registry)
                .increment();

        Timer.builder("loadbalancer_request_duration_seconds")
                .description("End-to-end time to serve a client request, including retries")
                .publishPercentileHistogram()
                .tag("algorithm", algorithm)
                .tag("method", normaliseMethod(method))
                .tag("status", String.valueOf(status))
                .tag("route", route)
                .register(registry)
                .record(durationNs, TimeUnit.NANOSECONDS);
    }

    /**
     * Records a request the ALB could not complete successfully.
     *
     * @param errorCode stable error code, e.g. {@code GATEWAY_TIMEOUT}
     */
    public void recordFailure(String errorCode, String route) {
        failedRequests.incrementAndGet();
        if ("NO_HEALTHY_BACKEND".equals(errorCode)) {
            noHealthyBackendRejections.incrementAndGet();
        }
        Counter.builder("loadbalancer_requests_failed_total")
                .description("Client requests that failed at the load balancer")
                .tag("error", errorCode)
                .tag("route", route)
                .register(registry)
                .increment();
    }

    /** Records that a request was retried against a different backend. */
    public void recordRetry(String reason) {
        Counter.builder("loadbalancer_retries_total")
                .description("Retry attempts issued to an alternative backend")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /** Records that the ALB shed a request because it was at its concurrency ceiling. */
    public void recordOverload() {
        Counter.builder("loadbalancer_overload_rejections_total")
                .description("Requests rejected because the concurrency limit was reached")
                .register(registry)
                .increment();
    }

    /** Records a circuit breaker transitioning into OPEN. */
    public void recordCircuitOpened(String backendId) {
        Counter.builder("loadbalancer_circuit_breaker_open_total")
                .description("Circuit breaker transitions into the OPEN state")
                .tag("backend", backendId)
                .register(registry)
                .increment();
    }

    public void incrementActive() {
        activeRequests.incrementAndGet();
    }

    public void decrementActive() {
        activeRequests.updateAndGet(value -> value > 0 ? value - 1 : 0);
    }

    public int activeRequests() {
        return activeRequests.get();
    }

    public long totalRequests() {
        return totalRequests.get();
    }

    public long failedRequests() {
        return failedRequests.get();
    }

    public long noHealthyBackendRejections() {
        return noHealthyBackendRejections.get();
    }

    private static String normaliseMethod(String method) {
        if (method == null) {
            return "OTHER";
        }
        String upper = method.toUpperCase(Locale.ROOT);
        return KNOWN_METHODS.contains(upper) ? upper : "OTHER";
    }
}
