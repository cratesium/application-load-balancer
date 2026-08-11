package com.example.loadbalancer.resilience;

import com.example.loadbalancer.config.LoadBalancerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * A per-backend circuit breaker: stops sending traffic to a backend that is failing, and
 * probes cautiously before trusting it again.
 *
 * <h2>What problem this actually solves</h2>
 * Health checks poll every few seconds; a backend can fail thousands of requests in that
 * gap. Worse, when a backend is overloaded, every request the ALB sends it makes recovery
 * slower — the classic retry storm that turns a slow server into a dead one. Opening the
 * circuit does two things at once: it fails fast for clients (a 502 in microseconds instead
 * of a 10-second timeout) and it removes the load that is preventing recovery.
 *
 * <h2>Why not resilience4j</h2>
 * Only a slice of it is needed and that slice needs to integrate with our own metrics,
 * admin API and per-backend registry. About 150 lines here are fully unit-testable with an
 * injected clock, whereas the library would add a dependency and still need a wrapper.
 *
 * <h2>The sliding window is 64 bits</h2>
 * Call outcomes are stored as bits in a single {@code long}: each result shifts the word
 * left and ORs in a 1 for failure. The failure count is {@link Long#bitCount} of the masked
 * word. That makes recording an outcome exactly one CAS on one atomic, with no per-call
 * allocation and no lock — which matters because this runs on Netty event-loop threads for
 * every proxied request. The cost is a hard 64-result ceiling on window size, which the
 * configuration validator enforces with an explicit message.
 *
 * <h2>Thread safety</h2>
 * All state transitions are CAS loops over one immutable {@link Status} record, so a
 * transition is atomic and indivisible: concurrent threads can never observe a
 * half-transitioned breaker, and exactly one thread wins the race to move CLOSED to OPEN
 * (which is what stops N threads all logging the same transition).
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    /** Maximum window size, imposed by the bitset representation. */
    public static final int MAX_WINDOW_SIZE = 64;

    private final String name;
    private final int windowSize;
    private final long windowMask;
    private final int minimumCalls;
    private final int failureRateThreshold;
    private final long openDurationNanos;
    private final int halfOpenMaxCalls;
    private final int halfOpenSuccessesToClose;
    private final LongSupplier clock;

    /** Bit i = outcome of the i-th most recent call (1 = failure). */
    private final AtomicLong window = new AtomicLong();
    /** Number of results recorded since the last reset, saturating at {@link #windowSize}. */
    private final AtomicLong recorded = new AtomicLong();

    private final AtomicReference<Status> status;
    private final AtomicLong openedCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();

    /**
     * Immutable state bundle, swapped atomically.
     *
     * @param state             current state
     * @param changedAtNanos    when the state was entered, on the injected clock
     * @param halfOpenPermits   probe slots still available in HALF_OPEN
     * @param halfOpenSuccesses probe successes accumulated in HALF_OPEN
     */
    private record Status(CircuitState state, long changedAtNanos, int halfOpenPermits, int halfOpenSuccesses) {
    }

    public CircuitBreaker(String name, LoadBalancerProperties.CircuitBreaker config) {
        this(name, config, System::nanoTime);
    }

    /**
     * @param clock nanosecond time source; injectable so tests can advance time
     *              deterministically instead of sleeping
     */
    public CircuitBreaker(String name, LoadBalancerProperties.CircuitBreaker config, LongSupplier clock) {
        this.name = Objects.requireNonNull(name, "name");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (config.slidingWindowSize() > MAX_WINDOW_SIZE) {
            throw new IllegalArgumentException("circuit-breaker.sliding-window-size must be <= "
                    + MAX_WINDOW_SIZE + " but was " + config.slidingWindowSize());
        }
        this.windowSize = config.slidingWindowSize();
        this.windowMask = windowSize == MAX_WINDOW_SIZE ? -1L : (1L << windowSize) - 1;
        this.minimumCalls = Math.min(config.minimumCalls(), config.slidingWindowSize());
        this.failureRateThreshold = config.failureRateThreshold();
        this.openDurationNanos = config.openDuration().toNanos();
        this.halfOpenMaxCalls = config.halfOpenMaxCalls();
        this.halfOpenSuccessesToClose = Math.min(config.halfOpenSuccessesToClose(), config.halfOpenMaxCalls());
        this.status = new AtomicReference<>(new Status(CircuitState.CLOSED, clock.getAsLong(), 0, 0));
    }

    /**
     * Asks permission to send a request to this backend.
     *
     * <p>Every {@code true} must be followed by exactly one {@link #onSuccess()} or
     * {@link #onFailure()}, or HALF_OPEN probe permits leak and the breaker can never close.
     *
     * @return true if the call may proceed
     */
    public boolean tryAcquire() {
        while (true) {
            Status current = status.get();
            switch (current.state()) {
                case CLOSED -> {
                    return true;
                }
                case OPEN -> {
                    if (clock.getAsLong() - current.changedAtNanos() < openDurationNanos) {
                        rejectedCount.incrementAndGet();
                        return false;
                    }
                    // Cooldown elapsed: exactly one thread wins the move to HALF_OPEN and
                    // consumes the first probe permit.
                    Status halfOpen = new Status(CircuitState.HALF_OPEN, clock.getAsLong(), halfOpenMaxCalls - 1, 0);
                    if (status.compareAndSet(current, halfOpen)) {
                        log.info("Circuit breaker '{}' OPEN -> HALF_OPEN, allowing up to {} probe call(s)",
                                name, halfOpenMaxCalls);
                        return true;
                    }
                }
                case HALF_OPEN -> {
                    if (current.halfOpenPermits() <= 0) {
                        rejectedCount.incrementAndGet();
                        return false;
                    }
                    Status consumed = new Status(current.state(), current.changedAtNanos(),
                            current.halfOpenPermits() - 1, current.halfOpenSuccesses());
                    if (status.compareAndSet(current, consumed)) {
                        return true;
                    }
                }
            }
        }
    }

    /** Records a successful call. */
    public void onSuccess() {
        while (true) {
            Status current = status.get();
            if (current.state() == CircuitState.HALF_OPEN) {
                int successes = current.halfOpenSuccesses() + 1;
                if (successes >= halfOpenSuccessesToClose) {
                    if (status.compareAndSet(current, new Status(CircuitState.CLOSED, clock.getAsLong(), 0, 0))) {
                        resetWindow();
                        log.info("Circuit breaker '{}' HALF_OPEN -> CLOSED after {} successful probe(s)",
                                name, successes);
                        return;
                    }
                } else {
                    Status updated = new Status(current.state(), current.changedAtNanos(),
                            current.halfOpenPermits(), successes);
                    if (status.compareAndSet(current, updated)) {
                        return;
                    }
                }
            } else {
                record(false);
                return;
            }
        }
    }

    /** Records a failed call, which may trip or re-trip the breaker. */
    public void onFailure() {
        while (true) {
            Status current = status.get();
            if (current.state() == CircuitState.HALF_OPEN) {
                // One failed probe is enough: the backend is still unwell, so back off for
                // another full cooldown rather than continuing to probe.
                if (status.compareAndSet(current, new Status(CircuitState.OPEN, clock.getAsLong(), 0, 0))) {
                    resetWindow();
                    openedCount.incrementAndGet();
                    log.warn("Circuit breaker '{}' HALF_OPEN -> OPEN after a failed probe", name);
                    return;
                }
            } else {
                record(true);
                return;
            }
        }
    }

    /** Records an outcome into the window and trips the breaker if the rate is exceeded. */
    private void record(boolean failure) {
        long updated = window.updateAndGet(bits -> ((bits << 1) | (failure ? 1L : 0L)) & windowMask);
        long count = recorded.updateAndGet(value -> Math.min(windowSize, value + 1));

        if (count < minimumCalls) {
            // Not enough evidence. Tripping on the first two failures after a restart would
            // take a healthy backend out of service on noise.
            return;
        }
        int failures = Long.bitCount(updated & maskFor(count));
        int rate = (int) ((failures * 100L) / count);
        if (rate < failureRateThreshold) {
            return;
        }
        Status current = status.get();
        if (current.state() != CircuitState.CLOSED) {
            return;
        }
        if (status.compareAndSet(current, new Status(CircuitState.OPEN, clock.getAsLong(), 0, 0))) {
            resetWindow();
            openedCount.incrementAndGet();
            log.warn("Circuit breaker '{}' CLOSED -> OPEN: {}% failure rate over last {} call(s) "
                    + "(threshold {}%), cooling down for {}ms",
                    name, rate, count, failureRateThreshold, openDurationNanos / 1_000_000);
        }
    }

    private long maskFor(long count) {
        return count >= MAX_WINDOW_SIZE ? -1L : (1L << count) - 1;
    }

    private void resetWindow() {
        window.set(0L);
        recorded.set(0L);
    }

    /**
     * @return the current state. In OPEN, this reports HALF_OPEN once the cooldown has
     *         elapsed even though no probe has been attempted yet, so that admin views and
     *         gauges do not show a stale OPEN for a breaker that is ready to retry.
     */
    public CircuitState state() {
        Status current = status.get();
        if (current.state() == CircuitState.OPEN
                && clock.getAsLong() - current.changedAtNanos() >= openDurationNanos) {
            return CircuitState.HALF_OPEN;
        }
        return current.state();
    }

    /** @return true if this backend should be excluded from routing right now. */
    public boolean isOpen() {
        return state() == CircuitState.OPEN;
    }

    /** Forces the breaker closed. Exposed for the admin API and for tests. */
    public void reset() {
        status.set(new Status(CircuitState.CLOSED, clock.getAsLong(), 0, 0));
        resetWindow();
        log.info("Circuit breaker '{}' manually reset to CLOSED", name);
    }

    public String name() {
        return name;
    }

    /** @return how many times this breaker has moved into OPEN. */
    public long openedCount() {
        return openedCount.get();
    }

    /** @return how many calls this breaker has rejected without attempting. */
    public long rejectedCount() {
        return rejectedCount.get();
    }
}
