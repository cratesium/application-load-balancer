package com.example.loadbalancer.backend;

/**
 * Notification that the pool changed.
 *
 * <p>Delivered synchronously to listeners registered with
 * {@link BackendRegistry#addListener}. Used by the metrics layer to create and remove
 * per-backend gauges, and by the HTTP client factory to evict the connection pool of a
 * backend that no longer exists. Listeners must not block: they run on whichever thread
 * performed the mutation (an admin request thread or the health-check scheduler).
 *
 * @param type     what happened
 * @param backend  the affected backend
 * @param previous state before the change (null for {@link Type#ADDED})
 * @param current  state after the change (null for {@link Type#REMOVED})
 */
public record BackendChangeEvent(Type type, BackendServer backend, BackendState previous, BackendState current) {

    public enum Type {
        ADDED,
        REMOVED,
        STATE_CHANGED,
        WEIGHT_CHANGED
    }

    /** Listener contract. Deliberately a single method so it can be a lambda in tests. */
    @FunctionalInterface
    public interface Listener {
        void onBackendChange(BackendChangeEvent event);
    }
}
