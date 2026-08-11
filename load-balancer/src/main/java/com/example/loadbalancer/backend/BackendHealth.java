package com.example.loadbalancer.backend;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Consecutive-probe counters implementing hysteresis for one backend.
 *
 * <p>A single failed probe is not evidence of an outage and a single successful probe
 * is not evidence of recovery. This class turns a stream of probe results into
 * transition <em>signals</em> only when the configured thresholds are crossed, which is
 * what stops a marginal backend from flapping in and out of the pool every few seconds.
 *
 * <p>Deciding the signal and applying the state change are deliberately separated: this
 * class owns the counters, {@link BackendRegistry} owns the state machine. That keeps the
 * threshold logic unit-testable without constructing a registry.
 *
 * <p>Thread safety: probes for one backend normally come from a single scheduler thread,
 * but passive failures arrive from arbitrary event-loop threads, so both counters are
 * atomic and each update resets its opposite.
 */
public final class BackendHealth {

    /** What the caller should do with the backend's state after a probe result. */
    public enum Signal {
        /** Threshold not crossed — leave the state alone. */
        NONE,
        /** Enough consecutive successes: the backend may be promoted to UP. */
        PROMOTE,
        /** Enough consecutive failures: the backend should be demoted to DOWN. */
        DEMOTE
    }

    private final int failureThreshold;
    private final int successThreshold;

    private final AtomicInteger consecutiveSuccesses = new AtomicInteger();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    public BackendHealth(int failureThreshold, int successThreshold) {
        if (failureThreshold < 1 || successThreshold < 1) {
            throw new IllegalArgumentException("Health check thresholds must be >= 1");
        }
        this.failureThreshold = failureThreshold;
        this.successThreshold = successThreshold;
    }

    /**
     * Records a successful probe.
     *
     * @return {@link Signal#PROMOTE} once {@code successThreshold} consecutive successes
     *         have been observed, otherwise {@link Signal#NONE}
     */
    public Signal recordSuccess() {
        consecutiveFailures.set(0);
        int successes = consecutiveSuccesses.incrementAndGet();
        return successes >= successThreshold ? Signal.PROMOTE : Signal.NONE;
    }

    /**
     * Records a failed probe.
     *
     * @return {@link Signal#DEMOTE} once {@code failureThreshold} consecutive failures
     *         have been observed, otherwise {@link Signal#NONE}
     */
    public Signal recordFailure() {
        consecutiveSuccesses.set(0);
        int failures = consecutiveFailures.incrementAndGet();
        return failures >= failureThreshold ? Signal.DEMOTE : Signal.NONE;
    }

    /**
     * Clears both counters. Called when a backend is administratively enabled or
     * disabled so that stale counts from a previous life cannot trigger an immediate
     * transition.
     */
    public void reset() {
        consecutiveSuccesses.set(0);
        consecutiveFailures.set(0);
    }

    public int consecutiveSuccesses() {
        return consecutiveSuccesses.get();
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    public int failureThreshold() {
        return failureThreshold;
    }

    public int successThreshold() {
        return successThreshold;
    }
}
