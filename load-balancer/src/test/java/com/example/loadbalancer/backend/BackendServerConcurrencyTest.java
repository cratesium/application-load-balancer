package com.example.loadbalancer.backend;

import com.example.loadbalancer.testsupport.TestBackends;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests for per-backend runtime state.
 *
 * <p>The active-connection counter is the most safety-critical mutable value in the system: it
 * drives least-connections routing and the drain decision. A lost increment skews routing; a
 * lost decrement is permanent and silently removes capacity from the pool.
 */
class BackendServerConcurrencyTest {

    @Test
    @DisplayName("1,000 concurrent acquire/release pairs leave the counter at exactly zero")
    void counterReturnsToZeroUnderConcurrency() throws Exception {
        BackendServer backend = TestBackends.backend("backend-1");
        int threads = 100;
        int perThread = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (var pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            backend.acquireConnection();
                            Thread.yield();
                            backend.releaseConnection();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(backend.activeConnections()).isZero();
        assertThat(backend.totalRequests()).isEqualTo((long) threads * perThread);
    }

    @Test
    @DisplayName("the counter never goes negative, even with unpaired releases")
    void counterNeverGoesNegative() throws Exception {
        BackendServer backend = TestBackends.backend("backend-1");
        int threads = 64;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (var pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                int threadId = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 200; i++) {
                            if (threadId % 3 == 0) {
                                // Deliberately unbalanced: more releases than acquires.
                                backend.releaseConnection();
                            } else {
                                backend.acquireConnection();
                                backend.releaseConnection();
                            }
                            assertThat(backend.activeConnections()).isNotNegative();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }

        // A negative count would make this backend look permanently idle and attract all
        // subsequent least-connections traffic, so the counter is clamped at zero.
        assertThat(backend.activeConnections()).isZero();
    }

    @Test
    @DisplayName("success and failure counters are exact under concurrent updates")
    void requestCountersAreExact() throws Exception {
        BackendServer backend = TestBackends.backend("backend-1");
        int threads = 50;
        int perThread = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (var pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            backend.acquireConnection();
                            if (i % 2 == 0) {
                                backend.recordSuccess();
                            } else {
                                backend.recordFailure("TEST");
                            }
                            backend.releaseConnection();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }

        long total = (long) threads * perThread;
        assertThat(backend.totalRequests()).isEqualTo(total);
        assertThat(backend.successfulRequests()).isEqualTo(total / 2);
        assertThat(backend.failedRequests()).isEqualTo(total / 2);
    }

    @Test
    @DisplayName("state transitions stay coherent while counters are being hammered")
    void stateStaysCoherentUnderLoad() throws Exception {
        BackendServer backend = TestBackends.backend("backend-1");
        int threads = 32;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try (var pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                int threadId = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 500; i++) {
                            if (threadId % 4 == 0) {
                                backend.state(ThreadLocalRandom.current().nextBoolean()
                                        ? BackendState.UP : BackendState.DOWN);
                            } else {
                                backend.acquireConnection();
                                // The state must always be one of the legal values; a torn
                                // read would surface as a null or an unexpected enum here.
                                assertThat(backend.state()).isIn(BackendState.UP, BackendState.DOWN);
                                backend.releaseConnection();
                            }
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(backend.activeConnections()).isZero();
    }

    @Test
    @DisplayName("weight changes are visible to readers immediately")
    void weightChangesArePublished() throws Exception {
        BackendServer backend = TestBackends.backend("backend-1", 1);
        CountDownLatch done = new CountDownLatch(2);

        try (var pool = Executors.newFixedThreadPool(2)) {
            pool.submit(() -> {
                for (int i = 1; i <= 1000; i++) {
                    backend.weight(1 + (i % 9));
                }
                done.countDown();
            });
            pool.submit(() -> {
                for (int i = 0; i < 1000; i++) {
                    assertThat(backend.weight()).isBetween(1, 9);
                }
                done.countDown();
            });
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }
    }
}
