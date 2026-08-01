package com.example.loadbalancer.health;

import com.example.loadbalancer.backend.BackendRegistry;
import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.config.LoadBalancerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Marks backends unhealthy based on real traffic, without waiting for the next active probe.
 *
 * <h2>Why active health checks are not enough</h2>
 * With a 5s interval and a threshold of 3, an active checker needs up to 15 seconds to
 * notice an outage. At 1,000 requests per second that is 15,000 failed requests through a
 * backend the ALB already had ample evidence about — every one of those failures was itself
 * a health signal. Real traffic is also a strictly better probe: it exercises the actual
 * endpoints with the actual payloads, whereas {@code GET /health} frequently returns 200
 * from a process whose database pool is exhausted.
 *
 * <h2>Why the window is time-based</h2>
 * "5 failures" is meaningless without a period — 5 failures in 2 seconds is an outage,
 * 5 failures over an hour is background noise. Failures are counted inside a rolling window
 * and the count resets when the window rolls over, so isolated failures can never accumulate
 * into a false positive. A success also resets the counter, since consecutive failures are
 * what indicate a broken backend rather than a scattered few.
 *
 * <h2>Recovery is deliberately not passive</h2>
 * This class only ever demotes. Promotion stays with the active checker, because a backend
 * that has been taken out of rotation receives no traffic — so there is no passive evidence
 * to recover from. That asymmetry is what stops a flapping backend from oscillating.
 */
@Component
public class PassiveHealthMonitor {

    private final BackendRegistry registry;
    private final boolean enabled;
    private final int failureThreshold;
    private final long windowNanos;
    private final LongSupplier clock;

    private final Map<String, AtomicReference<Window>> windows = new ConcurrentHashMap<>();

    /** Failure count and the start of the window it accumulated in. */
    private record Window(long startNanos, int failures) {
    }

    /** Production constructor. Annotated because the test constructor below is an overload. */
    @Autowired
    public PassiveHealthMonitor(LoadBalancerProperties properties, BackendRegistry registry) {
        this(properties, registry, System::nanoTime);
    }

    /** @param clock injectable time source so tests need no sleeping */
    public PassiveHealthMonitor(LoadBalancerProperties properties, BackendRegistry registry, LongSupplier clock) {
        this.registry = registry;
        this.enabled = properties.passiveHealth().enabled();
        this.failureThreshold = properties.passiveHealth().failureThreshold();
        this.windowNanos = properties.passiveHealth().window().toNanos();
        this.clock = clock;
    }

    /**
     * Records a failed proxied request against a backend, demoting it once the threshold is
     * crossed inside the window.
     *
     * @param reason failure classification, included in the state-change log line
     */
    public void recordFailure(BackendServer backend, String reason) {
        if (!enabled) {
            return;
        }
        long now = clock.getAsLong();
        AtomicReference<Window> ref = windows.computeIfAbsent(backend.id(),
                id -> new AtomicReference<>(new Window(now, 0)));

        Window updated = ref.updateAndGet(current ->
                now - current.startNanos() > windowNanos
                        ? new Window(now, 1)             // window expired: start a fresh one
                        : new Window(current.startNanos(), current.failures() + 1));

        if (updated.failures() >= failureThreshold) {
            ref.set(new Window(now, 0));
            registry.markUnhealthy(backend, "passive health check: " + updated.failures()
                    + " failure(s) within " + (windowNanos / 1_000_000) + "ms, last was " + reason);
        }
    }

    /** Records a successful proxied request, clearing any accumulated failures. */
    public void recordSuccess(BackendServer backend) {
        if (!enabled) {
            return;
        }
        AtomicReference<Window> ref = windows.get(backend.id());
        if (ref != null) {
            ref.set(new Window(clock.getAsLong(), 0));
        }
    }

    /** @return current failure count in the active window, for tests and diagnostics. */
    public int failureCount(String backendId) {
        AtomicReference<Window> ref = windows.get(backendId);
        return ref == null ? 0 : ref.get().failures();
    }

    public boolean isEnabled() {
        return enabled;
    }
}
