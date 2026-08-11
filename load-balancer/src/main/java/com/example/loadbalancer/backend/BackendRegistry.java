package com.example.loadbalancer.backend;

import com.example.loadbalancer.config.LoadBalancerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The authoritative owner of the backend pool and of every backend's state machine.
 *
 * <h2>Concurrency model</h2>
 * Reads are lock-free: {@link #snapshot()} is a single volatile read of an
 * {@link AtomicReference} holding an immutable {@link BackendSnapshot}. Writes (add,
 * remove, enable, disable, state transitions) serialise on one monitor. That asymmetry is
 * the point — writes happen a few times an hour, reads happen on every request, and a
 * read must never wait for an operator's {@code curl}.
 *
 * <p>Copy-on-write is used rather than a {@code ConcurrentHashMap} because routing needs a
 * <em>stable list</em> for the duration of a selection. Round-robin computing
 * {@code index % size} against a map that shrinks mid-iteration, or a retry landing on a
 * pool that changed shape between attempts, are exactly the bugs this avoids.
 *
 * <h2>Separation of responsibilities</h2>
 * Routing strategies receive a candidate list and return a choice; they never call into
 * this class. Health checkers report probe outcomes; they do not decide states. All state
 * transitions funnel through this one class so the legal-transition rules (a DISABLED
 * backend is never promoted by a health check, a DRAINING backend is never resurrected)
 * live in exactly one place.
 */
@Component
public class BackendRegistry {

    private static final Logger log = LoggerFactory.getLogger(BackendRegistry.class);

    private final LoadBalancerProperties properties;
    private final AtomicReference<BackendSnapshot> snapshot =
            new AtomicReference<>(BackendSnapshot.of(List.of(), 0L));
    private final AtomicLong version = new AtomicLong();
    private final List<BackendChangeEvent.Listener> listeners = new CopyOnWriteArrayList<>();

    /** Guards writes only. Never held while doing I/O. */
    private final Object writeLock = new Object();

    public BackendRegistry(LoadBalancerProperties properties) {
        this.properties = properties;
        for (LoadBalancerProperties.Backend backend : properties.backends()) {
            register(BackendSpec.from(backend));
        }
        log.info("Backend registry initialised with {} backend(s): {}",
                snapshot.get().size(),
                snapshot.get().backends().stream().map(BackendServer::id).toList());
    }

    // ------------------------------------------------------------------
    // Reads — hot path, lock-free
    // ------------------------------------------------------------------

    /** @return the current immutable membership snapshot. */
    public BackendSnapshot snapshot() {
        return snapshot.get();
    }

    /** @return every backend, regardless of state. */
    public List<BackendServer> all() {
        return snapshot.get().backends();
    }

    /**
     * @return only backends that may receive new requests (state {@code UP}).
     *         DOWN, DRAINING and DISABLED backends are filtered out here so that no
     *         strategy has to remember to check.
     */
    public List<BackendServer> routable() {
        List<BackendServer> all = snapshot.get().backends();
        List<BackendServer> result = new ArrayList<>(all.size());
        for (BackendServer backend : all) {
            if (backend.isRoutable()) {
                result.add(backend);
            }
        }
        return result;
    }

    public Optional<BackendServer> find(String id) {
        return Optional.ofNullable(snapshot.get().find(id));
    }

    /** @return the membership version; bumped on any change that affects routing. */
    public long version() {
        return version.get();
    }

    public void addListener(BackendChangeEvent.Listener listener) {
        listeners.add(listener);
        // Replay current membership so a late listener (e.g. metrics) is not missing gauges.
        // Guarded the same way as live delivery: a listener that throws during replay must not
        // propagate out of registration and abort application startup.
        for (BackendServer backend : snapshot.get().backends()) {
            deliver(listener, new BackendChangeEvent(
                    BackendChangeEvent.Type.ADDED, backend, null, backend.state()));
        }
    }

    // ------------------------------------------------------------------
    // Writes — control plane, serialised
    // ------------------------------------------------------------------

    /**
     * Adds a backend to the pool.
     *
     * @throws IllegalStateException if a backend with the same id already exists; ids are
     *                               identities, and silently overwriting one would orphan
     *                               the in-flight requests counted against it
     */
    public BackendServer register(BackendSpec spec) {
        BackendServer created;
        synchronized (writeLock) {
            BackendSnapshot current = snapshot.get();
            if (current.byId().containsKey(spec.id())) {
                throw new IllegalStateException("Backend with id '" + spec.id() + "' already exists");
            }
            created = new BackendServer(
                    spec.id(), spec.host(), spec.port(), spec.secure(), spec.weight(),
                    initialState(spec.enabled()),
                    new BackendHealth(properties.healthCheck().failureThreshold(),
                            properties.healthCheck().successThreshold()));
            List<BackendServer> next = new ArrayList<>(current.backends());
            next.add(created);
            publish(next);
        }
        log.info("Registered backend id={} url={} weight={} state={}",
                created.id(), created.baseUrl(), created.weight(), created.state());
        notifyListeners(new BackendChangeEvent(
                BackendChangeEvent.Type.ADDED, created, null, created.state()));
        return created;
    }

    /**
     * Removes a backend from the pool immediately.
     *
     * <p>Callers that want in-flight requests to finish first should go through the
     * draining coordinator instead of calling this directly.
     *
     * @return the removed backend, or empty if the id was unknown
     */
    public Optional<BackendServer> unregister(String id) {
        BackendServer removed = null;
        synchronized (writeLock) {
            BackendSnapshot current = snapshot.get();
            BackendServer existing = current.find(id);
            if (existing == null) {
                return Optional.empty();
            }
            removed = existing;
            List<BackendServer> next = new ArrayList<>(current.backends());
            next.remove(existing);
            publish(next);
        }
        log.info("Removed backend id={} (activeConnections={} at removal)",
                removed.id(), removed.activeConnections());
        notifyListeners(new BackendChangeEvent(
                BackendChangeEvent.Type.REMOVED, removed, removed.state(), null));
        return Optional.of(removed);
    }

    /**
     * Administratively enables a backend.
     *
     * <p>The backend is <em>not</em> put straight into rotation when active health checks
     * are on: it enters {@code DOWN} and must earn {@code UP} by passing probes. Sending
     * traffic to a server we have not verified since it was disabled is how a bad deploy
     * gets re-exposed to users.
     */
    public boolean enable(String id) {
        BackendServer backend = snapshot.get().find(id);
        if (backend == null) {
            return false;
        }
        BackendState previous = backend.state();
        if (previous == BackendState.UP) {
            return true;
        }
        backend.health().reset();
        // Deliberately NOT initialState(): assume-healthy-on-start exists to avoid a cold-start
        // window where the ALB boots with no routable backends at all. A re-enable is a
        // different situation — the rest of the pool is already serving — so the backend must
        // pass probes before receiving traffic again. Optimism at boot, verification on return.
        backend.state(properties.healthCheck().enabled() ? BackendState.DOWN : BackendState.UP);
        log.info("Backend id={} enabled ({} -> {})", id, previous, backend.state());
        bumpVersion();
        notifyListeners(new BackendChangeEvent(
                BackendChangeEvent.Type.STATE_CHANGED, backend, previous, backend.state()));
        return true;
    }

    /**
     * Administratively takes a backend out of rotation.
     *
     * <p>Moves it to {@link BackendState#DRAINING} so it stops receiving new requests
     * while its in-flight requests finish. The draining coordinator promotes it to
     * {@link BackendState#DISABLED} once it is quiet or the drain timeout expires.
     */
    public boolean disable(String id) {
        BackendServer backend = snapshot.get().find(id);
        if (backend == null) {
            return false;
        }
        BackendState previous = backend.state();
        if (previous == BackendState.DISABLED || previous == BackendState.DRAINING) {
            return true;
        }
        backend.state(BackendState.DRAINING);
        backend.health().reset();
        log.info("Backend id={} disabled, draining {} in-flight request(s)", id, backend.activeConnections());
        bumpVersion();
        notifyListeners(new BackendChangeEvent(
                BackendChangeEvent.Type.STATE_CHANGED, backend, previous, BackendState.DRAINING));
        return true;
    }

    /** Completes a drain by parking the backend in {@link BackendState#DISABLED}. */
    public void finishDraining(BackendServer backend) {
        if (backend.compareAndSetState(BackendState.DRAINING, BackendState.DISABLED)) {
            log.info("Backend id={} finished draining and is now DISABLED", backend.id());
            bumpVersion();
            notifyListeners(new BackendChangeEvent(BackendChangeEvent.Type.STATE_CHANGED,
                    backend, BackendState.DRAINING, BackendState.DISABLED));
        }
    }

    /**
     * Promotes a backend to {@code UP} after enough successful probes.
     *
     * <p>No-op for DISABLED and DRAINING backends: a health check must never override an
     * operator's decision to take a server out of service.
     */
    public void markHealthy(BackendServer backend, String reason) {
        backend.lastHealthCheck(Instant.now());
        BackendState previous = backend.state();
        if (previous == BackendState.DISABLED || previous == BackendState.DRAINING || previous == BackendState.UP) {
            return;
        }
        if (backend.compareAndSetState(previous, BackendState.UP)) {
            log.info("Backend id={} is UP ({} -> UP): {}", backend.id(), previous, reason);
            bumpVersion();
            notifyListeners(new BackendChangeEvent(
                    BackendChangeEvent.Type.STATE_CHANGED, backend, previous, BackendState.UP));
        }
    }

    /** Demotes a backend to {@code DOWN}. No-op for DISABLED/DRAINING, for the same reason. */
    public void markUnhealthy(BackendServer backend, String reason) {
        BackendState previous = backend.state();
        if (previous == BackendState.DISABLED || previous == BackendState.DRAINING || previous == BackendState.DOWN) {
            return;
        }
        if (backend.compareAndSetState(previous, BackendState.DOWN)) {
            log.warn("Backend id={} is DOWN ({} -> DOWN): {}", backend.id(), previous, reason);
            bumpVersion();
            notifyListeners(new BackendChangeEvent(
                    BackendChangeEvent.Type.STATE_CHANGED, backend, previous, BackendState.DOWN));
        }
    }

    /** Changes a backend's weight and makes weight-aware strategies rebuild. */
    public boolean updateWeight(String id, int weight) {
        BackendServer backend = snapshot.get().find(id);
        if (backend == null) {
            return false;
        }
        int previous = backend.weight();
        backend.weight(weight);
        log.info("Backend id={} weight changed {} -> {}", id, previous, weight);
        bumpVersion();
        notifyListeners(new BackendChangeEvent(
                BackendChangeEvent.Type.WEIGHT_CHANGED, backend, backend.state(), backend.state()));
        return true;
    }

    /**
     * Replaces the whole pool to match a new configuration, preserving the live counters
     * and health state of backends that are unchanged. Used by {@code /admin/config/reload}.
     *
     * @return ids that were added and removed, in that order
     */
    public ReconcileResult reconcile(List<BackendSpec> desired) {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<BackendServer> addedBackends = new ArrayList<>();
        List<BackendServer> removedBackends = new ArrayList<>();

        synchronized (writeLock) {
            BackendSnapshot current = snapshot.get();
            List<BackendServer> next = new ArrayList<>();
            for (BackendSpec spec : desired) {
                BackendServer existing = current.find(spec.id());
                if (existing != null && existing.host().equals(spec.host()) && existing.port() == spec.port()) {
                    // Same identity and address: keep the live object so counters survive.
                    if (existing.weight() != spec.weight()) {
                        existing.weight(spec.weight());
                    }
                    next.add(existing);
                } else {
                    if (existing != null) {
                        removed.add(existing.id());
                        removedBackends.add(existing);
                    }
                    BackendServer created = new BackendServer(
                            spec.id(), spec.host(), spec.port(), spec.secure(), spec.weight(),
                            initialState(spec.enabled()),
                            new BackendHealth(properties.healthCheck().failureThreshold(),
                                    properties.healthCheck().successThreshold()));
                    next.add(created);
                    added.add(created.id());
                    addedBackends.add(created);
                }
            }
            for (BackendServer existing : current.backends()) {
                if (desired.stream().noneMatch(spec -> spec.id().equals(existing.id()))) {
                    removed.add(existing.id());
                    removedBackends.add(existing);
                }
            }
            publish(next);
        }

        removedBackends.forEach(backend -> notifyListeners(new BackendChangeEvent(
                BackendChangeEvent.Type.REMOVED, backend, backend.state(), null)));
        addedBackends.forEach(backend -> notifyListeners(new BackendChangeEvent(
                BackendChangeEvent.Type.ADDED, backend, null, backend.state())));
        log.info("Reconciled backends: added={} removed={}", added, removed);
        return new ReconcileResult(List.copyOf(added), List.copyOf(removed));
    }

    /** Outcome of a {@link #reconcile(List)} call. */
    public record ReconcileResult(List<String> added, List<String> removed) {
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private BackendState initialState(boolean enabled) {
        if (!enabled) {
            return BackendState.DISABLED;
        }
        if (!properties.healthCheck().enabled()) {
            return BackendState.UP;
        }
        return properties.healthCheck().assumeHealthyOnStart() ? BackendState.UP : BackendState.DOWN;
    }

    /** Must be called while holding {@link #writeLock}. */
    private void publish(List<BackendServer> next) {
        snapshot.set(BackendSnapshot.of(next, version.incrementAndGet()));
    }

    /**
     * Republishes the current membership under a new version so that strategy caches
     * (hash ring, weighted schedule) rebuild after a state or weight change.
     *
     * <p>Takes the write lock even though membership is unchanged: without it, a
     * concurrent {@link #register} could publish its snapshot between this method's read
     * and write and be silently reverted.
     */
    private void bumpVersion() {
        synchronized (writeLock) {
            BackendSnapshot current = snapshot.get();
            snapshot.set(new BackendSnapshot(current.backends(), current.byId(), version.incrementAndGet()));
        }
    }

    private void notifyListeners(BackendChangeEvent event) {
        for (BackendChangeEvent.Listener listener : listeners) {
            deliver(listener, event);
        }
    }

    private void deliver(BackendChangeEvent.Listener listener, BackendChangeEvent event) {
        try {
            listener.onBackendChange(event);
        } catch (RuntimeException ex) {
            // A misbehaving listener must not break pool management: failing to update a gauge
            // is a cosmetic problem, failing to register a backend is an outage.
            log.warn("Backend change listener failed for event {} on backend {}",
                    event.type(), event.backend().id(), ex);
        }
    }
}
