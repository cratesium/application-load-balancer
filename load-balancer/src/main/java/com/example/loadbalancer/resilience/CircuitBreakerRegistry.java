package com.example.loadbalancer.resilience;

import com.example.loadbalancer.backend.BackendChangeEvent;
import com.example.loadbalancer.backend.BackendRegistry;
import com.example.loadbalancer.config.LoadBalancerProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One {@link CircuitBreaker} per backend, created on demand and destroyed with the backend.
 *
 * <h2>Why per backend and not global</h2>
 * A global breaker would let one broken backend stop traffic to the healthy ones — the
 * opposite of what a load balancer is for. Failure isolation is the entire point: backend-2
 * being down must have no effect on backend-1's traffic.
 *
 * <p>Breakers are keyed by backend id and removed when the backend is unregistered, so a
 * long-running ALB in an autoscaling environment does not accumulate breaker state for
 * thousands of dead instances. State is deliberately <em>not</em> preserved across
 * re-registration of the same id: a backend that was removed and re-added is a different
 * process and deserves a clean slate.
 *
 * <p>When the breaker is disabled in configuration, {@link #isAvailable} always returns true
 * and no breakers are created, so the feature costs nothing when switched off.
 */
@Component
public class CircuitBreakerRegistry {

    private final LoadBalancerProperties.CircuitBreaker config;
    private final boolean enabled;
    private final BackendRegistry backendRegistry;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakerRegistry(LoadBalancerProperties properties, BackendRegistry backendRegistry) {
        this.config = properties.circuitBreaker();
        this.enabled = properties.circuitBreaker().enabled();
        this.backendRegistry = backendRegistry;
    }

    @PostConstruct
    void subscribeToBackendChanges() {
        backendRegistry.addListener(this::onBackendChange);
    }

    private void onBackendChange(BackendChangeEvent event) {
        if (event.type() == BackendChangeEvent.Type.REMOVED) {
            breakers.remove(event.backend().id());
        }
    }

    /**
     * @return the breaker for a backend, creating it on first use. Never null even when the
     *         feature is disabled, so callers can report state uniformly.
     */
    public CircuitBreaker forBackend(String backendId) {
        return breakers.computeIfAbsent(backendId, id -> new CircuitBreaker(id, config));
    }

    /**
     * @return true if the backend is eligible for routing as far as the breaker is
     *         concerned. This is a pure query used during candidate filtering; it does not
     *         consume a HALF_OPEN probe permit — that happens in {@link #tryAcquire}
     *         immediately before the request is dispatched.
     */
    public boolean isAvailable(String backendId) {
        if (!enabled) {
            return true;
        }
        CircuitBreaker breaker = breakers.get(backendId);
        return breaker == null || !breaker.isOpen();
    }

    /** Consumes a call permit. See {@link CircuitBreaker#tryAcquire()}. */
    public boolean tryAcquire(String backendId) {
        return !enabled || forBackend(backendId).tryAcquire();
    }

    public void recordSuccess(String backendId) {
        if (enabled) {
            forBackend(backendId).onSuccess();
        }
    }

    public void recordFailure(String backendId) {
        if (enabled) {
            forBackend(backendId).onFailure();
        }
    }

    /** @return the breaker's state name, or {@code DISABLED} when the feature is off. */
    public String stateOf(String backendId) {
        if (!enabled) {
            return "DISABLED";
        }
        CircuitBreaker breaker = breakers.get(backendId);
        return breaker == null ? CircuitState.CLOSED.name() : breaker.state().name();
    }

    /** @return number of breakers currently in OPEN, for {@code /admin/status}. */
    public int openCircuitCount() {
        if (!enabled) {
            return 0;
        }
        return (int) breakers.values().stream().filter(CircuitBreaker::isOpen).count();
    }

    public long totalOpenedCount() {
        return breakers.values().stream().mapToLong(CircuitBreaker::openedCount).sum();
    }

    public Collection<CircuitBreaker> all() {
        return breakers.values();
    }

    public boolean isEnabled() {
        return enabled;
    }
}
