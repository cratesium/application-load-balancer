package com.example.loadbalancer.resilience;

import com.example.loadbalancer.config.LoadBalancerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Circuit breaker state-machine tests.
 *
 * <p>Time is driven by a fake clock rather than by sleeping. That keeps the suite fast and, more
 * importantly, makes it deterministic: a test that sleeps for the cooldown is a test that fails
 * on a loaded CI machine.
 */
class CircuitBreakerTest {

    private final AtomicLong now = new AtomicLong();

    private CircuitBreaker breaker(int windowSize, int minimumCalls, int failureRate,
                                   Duration openDuration, int halfOpenMaxCalls, int successesToClose) {
        LoadBalancerProperties.CircuitBreaker config = new LoadBalancerProperties.CircuitBreaker(
                true, windowSize, minimumCalls, failureRate, openDuration,
                halfOpenMaxCalls, successesToClose);
        return new CircuitBreaker("test-backend", config, now::get);
    }

    private CircuitBreaker defaultBreaker() {
        return breaker(10, 5, 50, Duration.ofSeconds(10), 3, 2);
    }

    private void advance(Duration duration) {
        now.addAndGet(duration.toNanos());
    }

    @Test
    @DisplayName("starts CLOSED and permits calls")
    void startsClosed() {
        CircuitBreaker breaker = defaultBreaker();

        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("CLOSED -> OPEN once the failure rate crosses the threshold")
    void opensOnFailureRate() {
        CircuitBreaker breaker = defaultBreaker();

        for (int i = 0; i < 5; i++) {
            breaker.tryAcquire();
            breaker.onFailure();
        }

        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.openedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("does not open before minimum-calls, however bad the early sample looks")
    void waitsForMinimumCalls() {
        CircuitBreaker breaker = breaker(20, 10, 50, Duration.ofSeconds(10), 3, 2);

        // 4 failures out of 4 is a 100% failure rate, but 4 calls is not evidence. Tripping
        // here would take a healthy backend out of service on startup noise.
        for (int i = 0; i < 4; i++) {
            breaker.tryAcquire();
            breaker.onFailure();
        }

        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("stays CLOSED when the failure rate stays below the threshold")
    void staysClosedBelowThreshold() {
        CircuitBreaker breaker = breaker(10, 5, 50, Duration.ofSeconds(10), 3, 2);

        // 2 failures out of 10 = 20%, and — importantly — the rate never exceeds 50% at any
        // point along the way. The window is evaluated after every call, so the ordering
        // matters, not just the final ratio.
        boolean[] failures = {false, false, true, false, false, false, true, false, false, false};
        for (boolean failure : failures) {
            breaker.tryAcquire();
            if (failure) {
                breaker.onFailure();
            } else {
                breaker.onSuccess();
            }
            assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        }
    }

    @Test
    @DisplayName("a burst of early failures trips the breaker even if the eventual rate is low")
    void tripsOnEarlyBurst() {
        CircuitBreaker breaker = breaker(10, 5, 50, Duration.ofSeconds(10), 3, 2);

        // 3 failures then successes. After the 5th call the recent window is 3/5 = 60%, so the
        // breaker opens — and it is right to. A backend that just failed three of the last five
        // requests is failing now; that the average recovers later is only knowable in hindsight.
        // This is why the window is a *sliding* one and is evaluated per call rather than being
        // averaged over a fixed period.
        for (int i = 0; i < 3; i++) {
            breaker.tryAcquire();
            breaker.onFailure();
        }
        for (int i = 0; i < 2; i++) {
            breaker.tryAcquire();
            breaker.onSuccess();
        }

        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    @DisplayName("OPEN rejects calls without attempting them")
    void openRejectsCalls() {
        CircuitBreaker breaker = defaultBreaker();
        trip(breaker);

        assertThat(breaker.tryAcquire()).isFalse();
        assertThat(breaker.tryAcquire()).isFalse();
        assertThat(breaker.rejectedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("OPEN -> HALF_OPEN after the cooldown elapses")
    void movesToHalfOpenAfterCooldown() {
        CircuitBreaker breaker = defaultBreaker();
        trip(breaker);
        assertThat(breaker.tryAcquire()).isFalse();

        advance(Duration.ofSeconds(11));

        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("HALF_OPEN admits at most half-open-max-calls probes")
    void halfOpenLimitsProbes() {
        CircuitBreaker breaker = breaker(10, 5, 50, Duration.ofSeconds(10), 3, 2);
        trip(breaker);
        advance(Duration.ofSeconds(11));

        assertThat(breaker.tryAcquire()).isTrue();   // probe 1 (also performs the transition)
        assertThat(breaker.tryAcquire()).isTrue();   // probe 2
        assertThat(breaker.tryAcquire()).isTrue();   // probe 3
        assertThat(breaker.tryAcquire()).isFalse();  // budget exhausted
    }

    @Test
    @DisplayName("HALF_OPEN -> CLOSED after enough successful probes")
    void closesAfterSuccessfulProbes() {
        CircuitBreaker breaker = defaultBreaker();
        trip(breaker);
        advance(Duration.ofSeconds(11));

        breaker.tryAcquire();
        breaker.onSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);

        breaker.tryAcquire();
        breaker.onSuccess();

        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("HALF_OPEN -> OPEN on a single failed probe")
    void reopensOnFailedProbe() {
        CircuitBreaker breaker = defaultBreaker();
        trip(breaker);
        advance(Duration.ofSeconds(11));

        breaker.tryAcquire();
        breaker.onSuccess();
        breaker.tryAcquire();
        breaker.onFailure();

        // One failure is enough: the backend is still unwell, so back off for a full cooldown
        // rather than continuing to probe a struggling server.
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
        assertThat(breaker.openedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("the cooldown restarts when a probe fails")
    void cooldownRestartsAfterFailedProbe() {
        CircuitBreaker breaker = defaultBreaker();
        trip(breaker);
        advance(Duration.ofSeconds(11));
        breaker.tryAcquire();
        breaker.onFailure();

        advance(Duration.ofSeconds(5));
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);

        advance(Duration.ofSeconds(6));
        assertThat(breaker.state()).isEqualTo(CircuitState.HALF_OPEN);
    }

    @Test
    @DisplayName("closing resets the window, so old failures cannot immediately re-trip it")
    void closingResetsTheWindow() {
        CircuitBreaker breaker = defaultBreaker();
        trip(breaker);
        advance(Duration.ofSeconds(11));
        breaker.tryAcquire();
        breaker.onSuccess();
        breaker.tryAcquire();
        breaker.onSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);

        // Four fresh failures: below minimum-calls for the reset window, so still CLOSED.
        for (int i = 0; i < 4; i++) {
            breaker.tryAcquire();
            breaker.onFailure();
        }
        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
    }

    @Test
    @DisplayName("manual reset forces the breaker closed")
    void manualResetClosesBreaker() {
        CircuitBreaker breaker = defaultBreaker();
        trip(breaker);
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);

        breaker.reset();

        assertThat(breaker.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("rejects a window larger than the 64-bit bitset can hold")
    void rejectsOversizedWindow() {
        LoadBalancerProperties.CircuitBreaker config = new LoadBalancerProperties.CircuitBreaker(
                true, 100, 10, 50, Duration.ofSeconds(10), 3, 2);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new CircuitBreaker("x", config, now::get));
    }

    @Test
    @DisplayName("only one of many concurrent failures wins the transition to OPEN")
    void transitionsExactlyOnceUnderConcurrency() throws Exception {
        CircuitBreaker breaker = breaker(64, 10, 50, Duration.ofSeconds(10), 3, 2);
        int threads = 64;
        var start = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(threads);

        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        breaker.tryAcquire();
                        breaker.onFailure();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }

        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
        // CAS on one immutable status record: many threads observe the threshold, exactly one
        // performs the transition. Without that, the breaker would log and count N openings.
        assertThat(breaker.openedCount()).isEqualTo(1);
    }

    /** Drives the breaker into OPEN using the default configuration. */
    private void trip(CircuitBreaker breaker) {
        for (int i = 0; i < 5; i++) {
            breaker.tryAcquire();
            breaker.onFailure();
        }
        assertThat(breaker.state()).isEqualTo(CircuitState.OPEN);
    }
}
