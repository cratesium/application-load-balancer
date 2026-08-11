package com.example.loadbalancer.lifecycle;

import com.example.loadbalancer.config.LoadBalancerProperties;
import com.example.loadbalancer.metrics.LoadBalancerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Drains the load balancer on SIGTERM instead of dropping in-flight requests.
 *
 * <h2>Ordering, which is the entire difficulty</h2>
 * <pre>
 *   SIGTERM
 *     │
 *     ├─1─ mark NOT READY            ← upstream stops sending new requests
 *     │                                 (this must come first, and must be visible
 *     │                                  for at least one upstream probe interval)
 *     ├─2─ stop accepting requests   ← ProxyWebFilter now sheds anything new
 *     ├─3─ wait for in-flight to reach zero, bounded by grace-period
 *     ├─4─ web server stops accepting connections   (Spring Boot graceful shutdown)
 *     ├─5─ connection pools disposed                (bean destruction)
 *     └─6─ exit
 * </pre>
 * Doing 4 before 1 is the classic mistake: the socket closes while an upstream proxy still
 * believes this instance is healthy, so it keeps opening connections and gets
 * connection-refused. From a client's perspective a deploy then looks like an outage.
 *
 * <p>This runs as a {@link SmartLifecycle} with the lowest phase, so it stops <em>before</em>
 * the web server does — Spring stops lifecycle beans in descending phase order, and the web
 * server lives in a high phase. Bean destruction, which disposes the connection pools, happens
 * after all of this, so nothing tears a pool out from under a request that is still using it.
 *
 * <p>Waiting here does block a thread, deliberately: it is Spring's shutdown thread, there are
 * no requests left to serve on it, and the alternative — returning immediately — would let the
 * JVM exit while requests are still running.
 */
@Component
public class GracefulShutdownHandler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownHandler.class);

    /** How often to re-check the in-flight count while draining. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    /**
     * Pause after marking not-ready, before shedding traffic, giving an upstream health check
     * a chance to observe the state change.
     */
    private static final Duration READINESS_PROPAGATION_DELAY = Duration.ofMillis(500);

    private final ReadinessManager readinessManager;
    private final LoadBalancerMetrics metrics;
    private final Duration gracePeriod;
    private volatile boolean running;

    public GracefulShutdownHandler(ReadinessManager readinessManager,
                                   LoadBalancerMetrics metrics,
                                   LoadBalancerProperties properties) {
        this.readinessManager = readinessManager;
        this.metrics = metrics;
        this.gracePeriod = properties.shutdown().gracePeriod();
    }

    @Override
    public void start() {
        running = true;
        log.info("Graceful shutdown armed with a grace period of {}", gracePeriod);
    }

    @Override
    public void stop() {
        running = false;
        readinessManager.stopAcceptingTraffic("shutdown signal received");
        sleep(READINESS_PROPAGATION_DELAY);
        awaitInFlightCompletion();
    }

    private void awaitInFlightCompletion() {
        long deadline = System.nanoTime() + gracePeriod.toNanos();
        int active = metrics.activeRequests();
        if (active == 0) {
            log.info("Shutdown: no in-flight requests, stopping immediately");
            return;
        }
        log.info("Shutdown: waiting up to {} for {} in-flight request(s) to complete", gracePeriod, active);

        while (System.nanoTime() < deadline) {
            active = metrics.activeRequests();
            if (active == 0) {
                log.info("Shutdown: all in-flight requests completed");
                return;
            }
            sleep(POLL_INTERVAL);
        }
        log.warn("Shutdown: grace period of {} expired with {} request(s) still in flight; "
                        + "these will be terminated",
                gracePeriod, metrics.activeRequests());
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * @return the lowest phase, so this bean stops first — before the web server closes its
     *         listener and before the connection pools are disposed
     */
    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }
}
