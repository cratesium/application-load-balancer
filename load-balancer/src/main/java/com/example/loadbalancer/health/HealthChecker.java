package com.example.loadbalancer.health;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.config.LoadBalancerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Set;

/**
 * Performs a single health probe against one backend.
 *
 * <p>Split from the scheduler so that "what a probe is" and "when probes happen" are separately
 * testable — this class can be pointed at a stub server in a unit test with no timers involved.
 *
 * <h2>Probe semantics</h2>
 * A probe succeeds if the backend answers with an acceptable status inside the response
 * timeout. Anything else — connection refused, timeout, 500, TLS failure — is a failure. The
 * response body is explicitly discarded rather than parsed: a health check that depends on body
 * content is a health check that breaks when someone changes a JSON field, and an unconsumed
 * body would hold its connection out of the pool.
 *
 * <p>{@code healthy-statuses} defaults to "any 2xx". Configuring it explicitly is useful for
 * backends that signal readiness with something other than 200 — for instance a service that
 * returns 204 when healthy, or one where 401 from an unauthenticated probe still proves the
 * process is serving.
 */
@Component
public class HealthChecker {

    private static final Logger log = LoggerFactory.getLogger(HealthChecker.class);

    private final WebClient webClient;
    private final String path;
    private final Duration responseTimeout;
    private final Set<Integer> healthyStatuses;

    public HealthChecker(HttpClient healthCheckHttpClient, LoadBalancerProperties properties) {
        LoadBalancerProperties.HealthCheck config = properties.healthCheck();
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(healthCheckHttpClient))
                .build();
        this.path = config.path().startsWith("/") ? config.path() : "/" + config.path();
        this.responseTimeout = config.responseTimeout();
        this.healthyStatuses = Set.copyOf(config.healthyStatuses());
    }

    /**
     * Probes a backend.
     *
     * @return a Mono of the probe outcome. Never fails — a transport error <em>is</em> the
     *         result, so the caller does not have to distinguish "probe failed" from "probe
     *         could not be performed"
     */
    public Mono<ProbeResult> probe(BackendServer backend) {
        String url = backend.baseUrl() + path;
        long start = System.nanoTime();

        return webClient.get()
                .uri(url)
                .exchangeToMono(response -> response.releaseBody()
                        .thenReturn(response.statusCode().value()))
                .timeout(responseTimeout)
                .map(status -> {
                    long durationNs = System.nanoTime() - start;
                    boolean healthy = isHealthy(status);
                    return new ProbeResult(healthy, status,
                            healthy ? null : "unexpected status " + status, durationNs);
                })
                .onErrorResume(error -> {
                    long durationNs = System.nanoTime() - start;
                    log.debug("Health probe failed for backend id={} url={}", backend.id(), url, error);
                    return Mono.just(new ProbeResult(false, -1, describe(error), durationNs));
                });
    }

    private boolean isHealthy(int status) {
        if (healthyStatuses.isEmpty()) {
            return status >= 200 && status < 300;
        }
        return healthyStatuses.contains(status);
    }

    /** Short, log-friendly description of a probe failure. */
    private static String describe(Throwable error) {
        String message = error.getMessage();
        String type = error.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    /**
     * The outcome of one probe.
     *
     * @param healthy    whether the backend answered acceptably
     * @param status     HTTP status, or -1 if no response was received
     * @param failure    description of the failure, or null on success
     * @param durationNs how long the probe took
     */
    public record ProbeResult(boolean healthy, int status, String failure, long durationNs) {

        /** @return a description suitable for a state-change log line. */
        public String reason() {
            return healthy ? "probe returned " + status : (failure == null ? "probe failed" : failure);
        }
    }
}
