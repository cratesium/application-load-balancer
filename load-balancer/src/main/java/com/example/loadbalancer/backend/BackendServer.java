package com.example.loadbalancer.backend;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * A backend server: stable identity plus live runtime state.
 *
 * <h2>Why this object is mutable when the registry snapshot is immutable</h2>
 * The {@link BackendRegistry} publishes an immutable <em>membership</em> snapshot so that
 * request threads can read the pool without locking. The per-backend counters, however,
 * must <em>not</em> be part of that snapshot: if adding an unrelated backend produced
 * fresh {@code BackendServer} instances, every in-flight request would decrement a
 * counter on an object nobody reads any more and the active-connection count would drift
 * permanently. So identity and address are final, and everything that changes per request
 * is an atomic held on this stable instance. Membership is copy-on-write; runtime state
 * is CAS-updated in place.
 *
 * <p>This class is never serialised to clients. The admin API projects it into an
 * immutable DTO so that no caller can reach in and mutate a counter.
 */
public final class BackendServer {

    private final String id;
    private final String host;
    private final int port;
    private final boolean secure;
    private final String baseUrl;

    // --- operator-controlled, changeable at runtime ---
    private final AtomicInteger weight;
    private final AtomicReference<BackendState> state;

    // --- runtime counters ---
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder successfulRequests = new LongAdder();
    private final LongAdder failedRequests = new LongAdder();

    private volatile Instant lastHealthCheck;
    private volatile Instant lastFailure;
    private volatile Instant lastStateChange;
    private volatile String lastFailureReason;

    private final BackendHealth health;

    public BackendServer(String id,
                         String host,
                         int port,
                         boolean secure,
                         int weight,
                         BackendState initialState,
                         BackendHealth health) {
        this.id = Objects.requireNonNull(id, "id");
        this.host = Objects.requireNonNull(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Backend '" + id + "' port must be in 1..65535 but was " + port);
        }
        if (weight < 1) {
            throw new IllegalArgumentException("Backend '" + id + "' weight must be >= 1 but was " + weight);
        }
        this.port = port;
        this.secure = secure;
        this.weight = new AtomicInteger(weight);
        this.state = new AtomicReference<>(Objects.requireNonNull(initialState, "initialState"));
        this.health = Objects.requireNonNull(health, "health");
        this.baseUrl = (secure ? "https://" : "http://") + host + ":" + port;
        this.lastStateChange = Instant.now();
    }

    public String id() {
        return id;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean secure() {
        return secure;
    }

    /** @return scheme://host:port with no trailing slash, e.g. {@code http://cde:8080}. */
    public String baseUrl() {
        return baseUrl;
    }

    /** @return {@code host:port}, suitable for a rewritten {@code Host} header. */
    public String authority() {
        return host + ":" + port;
    }

    public int weight() {
        return weight.get();
    }

    /**
     * Updates the routing weight. Weight-aware strategies pick the change up on their
     * next rebuild, which the registry triggers by bumping its version.
     */
    public void weight(int newWeight) {
        if (newWeight < 1) {
            throw new IllegalArgumentException("Weight must be >= 1 but was " + newWeight);
        }
        weight.set(newWeight);
    }

    public BackendState state() {
        return state.get();
    }

    /**
     * Atomically moves the backend from {@code expected} to {@code next}.
     *
     * @return true if this call performed the transition
     */
    public boolean compareAndSetState(BackendState expected, BackendState next) {
        boolean changed = state.compareAndSet(expected, next);
        if (changed) {
            lastStateChange = Instant.now();
        }
        return changed;
    }

    /** Unconditionally sets the state. Prefer {@link #compareAndSetState} on hot paths. */
    public void state(BackendState next) {
        BackendState previous = state.getAndSet(next);
        if (previous != next) {
            lastStateChange = Instant.now();
        }
    }

    /** @return true when this backend may receive new requests. */
    public boolean isRoutable() {
        return state.get().isRoutable();
    }

    public boolean isEnabled() {
        return state.get() != BackendState.DISABLED;
    }

    public BackendHealth health() {
        return health;
    }

    // ------------------------------------------------------------------
    // Request accounting
    // ------------------------------------------------------------------

    /**
     * Marks the start of a proxied request. Every call <strong>must</strong> be paired
     * with {@link #releaseConnection()} from a {@code doFinally}, including on cancel and
     * error, or least-connections routing will progressively blackhole this backend.
     *
     * @return the number of in-flight requests after this one was counted
     */
    public int acquireConnection() {
        totalRequests.increment();
        return activeConnections.incrementAndGet();
    }

    /**
     * Marks the end of a proxied request.
     *
     * <p>The counter is clamped at zero. A negative count can only result from an
     * unpaired release, and letting it go negative would make this backend look
     * permanently idle and attract all subsequent traffic.
     */
    public void releaseConnection() {
        activeConnections.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    public int activeConnections() {
        return activeConnections.get();
    }

    public void recordSuccess() {
        successfulRequests.increment();
    }

    public void recordFailure(String reason) {
        failedRequests.increment();
        lastFailure = Instant.now();
        lastFailureReason = reason;
    }

    public long totalRequests() {
        return totalRequests.sum();
    }

    public long successfulRequests() {
        return successfulRequests.sum();
    }

    public long failedRequests() {
        return failedRequests.sum();
    }

    public Instant lastHealthCheck() {
        return lastHealthCheck;
    }

    public void lastHealthCheck(Instant instant) {
        this.lastHealthCheck = instant;
    }

    public Instant lastFailure() {
        return lastFailure;
    }

    public Instant lastStateChange() {
        return lastStateChange;
    }

    public String lastFailureReason() {
        return lastFailureReason;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BackendServer other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "BackendServer[" + id + " " + baseUrl + " state=" + state.get()
                + " weight=" + weight.get() + " active=" + activeConnections.get() + "]";
    }
}
