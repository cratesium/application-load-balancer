package com.example.loadbalancer.backend;

/**
 * Lifecycle state of a single backend server.
 *
 * <p>Only {@link #UP} backends are eligible for routing. The distinction between
 * {@link #DOWN} and {@link #DISABLED} matters operationally: {@code DOWN} is decided by
 * the health checker and is automatically reversible, whereas {@code DISABLED} is an
 * operator decision that health checks must never undo.
 */
public enum BackendState {

    /** Passing health checks and accepting new requests. */
    UP(true),

    /** Failing health checks. Excluded from routing until it recovers. */
    DOWN(false),

    /**
     * Being taken out of service. New requests are not routed here, but in-flight
     * requests are allowed to finish before the backend is removed.
     */
    DRAINING(false),

    /** Administratively disabled. Health checks continue but cannot promote it to UP. */
    DISABLED(false);

    private final boolean routable;

    BackendState(boolean routable) {
        this.routable = routable;
    }

    /** @return true if a backend in this state may receive <em>new</em> requests. */
    public boolean isRoutable() {
        return routable;
    }
}
