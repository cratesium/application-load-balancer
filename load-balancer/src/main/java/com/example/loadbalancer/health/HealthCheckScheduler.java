package com.example.loadbalancer.health;

import com.example.loadbalancer.backend.BackendHealth;
import com.example.loadbalancer.backend.BackendRegistry;
import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.backend.BackendState;
import com.example.loadbalancer.config.LoadBalancerProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Drives active health checks on a fixed interval.
 *
 * <h2>Why all backends are probed concurrently</h2>
 * Probing sequentially would make one slow backend delay every subsequent probe: with a 3s
 * response timeout and 10 backends, the effective interval for the last one becomes 30 seconds
 * regardless of what was configured. {@code flatMap} fans out so the round takes as long as the
 * slowest single probe, not the sum.
 *
 * <h2>Why rounds cannot overlap</h2>
 * {@code concatMap} over the interval ticks means a new round never starts while the previous
 * one is still running. Without that, a pool of unresponsive backends accumulates overlapping
 * rounds — each holding connections and pending timers — and the health checker becomes its own
 * source of load at exactly the moment the system is already struggling. Skipped ticks are the
 * correct response to a slow round; piling up is not.
 *
 * <h2>Interaction with the state machine</h2>
 * This class reports probe results to {@link BackendHealth} and forwards the resulting
 * <em>signal</em> to the registry. It never sets a state itself, which is what keeps the rules
 * about DISABLED and DRAINING backends in one place.
 */
@Component
public class HealthCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckScheduler.class);

    private final BackendRegistry registry;
    private final HealthChecker healthChecker;
    private final LoadBalancerProperties.HealthCheck config;

    private volatile Disposable subscription;

    public HealthCheckScheduler(BackendRegistry registry,
                                HealthChecker healthChecker,
                                LoadBalancerProperties properties) {
        this.registry = registry;
        this.healthChecker = healthChecker;
        this.config = properties.healthCheck();
    }

    @PostConstruct
    void start() {
        if (!config.enabled()) {
            log.warn("Active health checks are DISABLED. Backends will only be marked unhealthy by "
                    + "passive failure detection, and a backend that recovers will not be detected.");
            return;
        }
        log.info("Active health checks: GET {} every {} (connect timeout {}, response timeout {}, "
                        + "failure threshold {}, success threshold {})",
                config.path(), config.interval(), config.connectTimeout(), config.responseTimeout(),
                config.failureThreshold(), config.successThreshold());

        Duration initialDelay = config.initialDelay();
        subscription = Flux.interval(initialDelay, config.interval())
                // concatMap: never start a round before the previous one finished.
                .concatMap(tick -> runRound(), 0)
                .subscribe(
                        ignored -> {
                        },
                        error -> log.error("Health check loop terminated unexpectedly; backend states will "
                                + "no longer be updated by active checks", error));
    }

    /**
     * Probes every backend once, immediately.
     *
     * <p>Public so that a health round can be triggered on demand rather than only on the timer:
     * useful after registering a backend (evaluate it now instead of waiting up to a full
     * interval) and essential for tests, which drive transitions as explicit counted steps
     * rather than by sleeping long enough to hope the prober ran.
     *
     * @return a Mono completing when the round finishes; errors from individual probes are
     *         already absorbed by {@link HealthChecker}
     */
    public Mono<Void> runRound() {
        List<BackendServer> backends = registry.all();
        if (backends.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(backends)
                .flatMap(this::probeAndApply, Math.min(backends.size(), 32))
                .then();
    }

    private Mono<Void> probeAndApply(BackendServer backend) {
        return healthChecker.probe(backend)
                .doOnNext(result -> apply(backend, result))
                .then();
    }

    /** Feeds one probe result into the backend's hysteresis counters and acts on the signal. */
    private void apply(BackendServer backend, HealthChecker.ProbeResult result) {
        backend.lastHealthCheck(Instant.now());

        // Probing a DISABLED or DRAINING backend is still worth doing — it keeps lastHealthCheck
        // fresh for operators — but its result must never move the state.
        BackendState state = backend.state();
        if (state == BackendState.DISABLED || state == BackendState.DRAINING) {
            return;
        }

        BackendHealth health = backend.health();
        BackendHealth.Signal signal = result.healthy() ? health.recordSuccess() : health.recordFailure();

        switch (signal) {
            case PROMOTE -> registry.markHealthy(backend, "active health check: " + result.reason()
                    + " (" + health.consecutiveSuccesses() + " consecutive success(es))");
            case DEMOTE -> registry.markUnhealthy(backend, "active health check: " + result.reason()
                    + " (" + health.consecutiveFailures() + " consecutive failure(s))");
            case NONE -> {
                if (!result.healthy()) {
                    log.debug("Backend id={} failed a health probe ({}/{} consecutive failures): {}",
                            backend.id(), health.consecutiveFailures(), config.failureThreshold(),
                            result.reason());
                }
            }
        }
    }

    @PreDestroy
    void stop() {
        Disposable current = subscription;
        if (current != null && !current.isDisposed()) {
            current.dispose();
            log.info("Active health checks stopped");
        }
    }

    /** @return true if the scheduler is currently running. */
    public boolean isRunning() {
        Disposable current = subscription;
        return current != null && !current.isDisposed();
    }
}
