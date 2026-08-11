package com.example.loadbalancer.lifecycle;

import com.example.loadbalancer.backend.BackendRegistry;
import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.backend.BackendState;
import com.example.loadbalancer.config.LoadBalancerProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Takes backends out of service without dropping the requests they are already serving.
 *
 * <h2>The problem</h2>
 * Removing a backend the instant an operator asks is the easy implementation and the wrong
 * one. Requests in flight to that backend are mid-response; cutting them off turns a routine
 * deploy into a burst of client-visible 502s. And this is the common case, not an edge case —
 * every rolling upgrade removes every backend in turn.
 *
 * <h2>The three phases</h2>
 * <pre>
 *   UP  ──disable──▶  DRAINING  ──in-flight reaches 0, or timeout──▶  DISABLED  ──▶ (optional) removed
 *                        │
 *                        └── excluded from routing: no NEW requests,
 *                            existing ones run to completion
 * </pre>
 * The state itself does the work: {@link BackendState#DRAINING} is not routable, so selection
 * skips it from the moment it is set, while the active-connection counter tells us when the
 * last in-flight request has finished. Polling that counter is adequate and simple — the
 * alternative, a callback from the last completing request, adds coupling to the data plane
 * for a control-plane concern.
 *
 * <p>The timeout is a backstop. A backend serving a long-lived stream, or a stuck request,
 * would otherwise drain forever and block a deploy. When it expires the backend is retired
 * anyway and the fact is logged, because an operator needs to know that some requests were
 * cut off rather than being told the drain "succeeded".
 */
@Component
public class DrainingCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DrainingCoordinator.class);

    private final BackendRegistry registry;
    private final Duration drainTimeout;
    private final Duration checkInterval;

    /** Active drain watchers, keyed by backend id, so a repeated disable does not double-poll. */
    private final Map<String, Disposable> watchers = new ConcurrentHashMap<>();

    public DrainingCoordinator(BackendRegistry registry, LoadBalancerProperties properties) {
        this.registry = registry;
        this.drainTimeout = properties.draining().timeout();
        this.checkInterval = properties.draining().checkInterval();
    }

    /**
     * Starts draining a backend and parks it in {@code DISABLED} when it is quiet.
     *
     * @param backend the backend to drain, already moved to {@code DRAINING} by the registry
     */
    public void drain(BackendServer backend) {
        watch(backend, false);
    }

    /**
     * Starts draining a backend and removes it from the pool entirely once quiet.
     *
     * <p>Used by {@code DELETE /admin/backends/{id}}: the caller's intent is removal, but the
     * removal waits for in-flight work.
     */
    public void drainAndRemove(BackendServer backend) {
        watch(backend, true);
    }

    private void watch(BackendServer backend, boolean removeWhenDone) {
        String id = backend.id();
        Disposable existing = watchers.remove(id);
        if (existing != null) {
            existing.dispose();
        }
        int initialInFlight = backend.activeConnections();
        log.info("Draining backend id={} with {} in-flight request(s), timeout={}",
                id, initialInFlight, drainTimeout);

        if (initialInFlight == 0) {
            complete(backend, removeWhenDone, 0, false);
            return;
        }

        Disposable subscription = Flux.interval(checkInterval, checkInterval)
                .map(tick -> backend.activeConnections())
                .filter(active -> active == 0)
                .next()
                .timeout(drainTimeout, Mono.just(-1))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(active -> {
                    boolean timedOut = active < 0;
                    complete(backend, removeWhenDone, backend.activeConnections(), timedOut);
                }, error -> log.warn("Drain watcher for backend id={} failed", id, error));

        watchers.put(id, subscription);
    }

    private void complete(BackendServer backend, boolean removeWhenDone, int remaining, boolean timedOut) {
        watchers.remove(backend.id());
        if (timedOut) {
            log.warn("Drain timeout ({}) expired for backend id={} with {} request(s) still in flight; "
                            + "retiring it anyway",
                    drainTimeout, backend.id(), remaining);
        } else {
            log.info("Backend id={} drained cleanly", backend.id());
        }
        registry.finishDraining(backend);
        if (removeWhenDone) {
            registry.unregister(backend.id());
        }
    }

    /** @return true if a drain is currently in progress for this backend. */
    public boolean isDraining(String backendId) {
        return watchers.containsKey(backendId);
    }

    @PreDestroy
    void cancelWatchers() {
        watchers.values().forEach(Disposable::dispose);
        watchers.clear();
    }
}
