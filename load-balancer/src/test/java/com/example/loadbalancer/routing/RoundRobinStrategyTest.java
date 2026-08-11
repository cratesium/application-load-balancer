package com.example.loadbalancer.routing;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.testsupport.TestBackends;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RoundRobinStrategyTest {

    private final RoundRobinStrategy strategy = new RoundRobinStrategy();

    @Test
    @DisplayName("cycles through backends in order, as the requirements' example specifies")
    void cyclesInOrder() {
        List<BackendServer> backends = TestBackends.backends(2);

        List<String> selected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            selected.add(strategy.selectBackend(backends, TestBackends.context("client")).id());
        }

        assertThat(selected).containsExactly(
                "backend-1", "backend-2", "backend-1", "backend-2", "backend-1");
    }

    @Test
    @DisplayName("distributes 10,000 requests exactly evenly across three backends")
    void distributesEvenly() {
        List<BackendServer> backends = TestBackends.backends(3);
        Map<String, Integer> counts = new HashMap<>();

        for (int i = 0; i < 10_000; i++) {
            String id = strategy.selectBackend(backends, TestBackends.context("client")).id();
            counts.merge(id, 1, Integer::sum);
        }

        // Round robin is deterministic, so the split is exact: 10000 = 3334 + 3333 + 3333.
        assertThat(counts).containsOnlyKeys("backend-1", "backend-2", "backend-3");
        assertThat(counts.values()).allSatisfy(count -> assertThat(count).isBetween(3333, 3334));
        assertThat(counts.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("never returns a backend outside the candidate list")
    void alwaysReturnsACandidate() {
        List<BackendServer> backends = TestBackends.backends(4);
        for (int i = 0; i < 1000; i++) {
            assertThat(backends).contains(strategy.selectBackend(backends, TestBackends.context("c")));
        }
    }

    @Test
    @DisplayName("stays balanced under 1,000 concurrent threads: no lost increments")
    void isThreadSafeUnderConcurrency() throws Exception {
        int threads = 100;
        int perThread = 100;
        int totalRequests = threads * perThread;
        List<BackendServer> backends = TestBackends.backends(4);
        Map<String, AtomicLong> counts = new ConcurrentHashMap<>();
        backends.forEach(backend -> counts.put(backend.id(), new AtomicLong()));

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            BackendServer chosen = strategy.selectBackend(
                                    backends, TestBackends.context("client"));
                            counts.get(chosen.id()).incrementAndGet();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }

        long sum = counts.values().stream().mapToLong(AtomicLong::get).sum();
        assertThat(sum).isEqualTo(totalRequests);

        // With an atomic cursor every index is handed out exactly once per cycle, so the
        // split must be perfectly even. A plain int here would lose increments and skew this.
        long expected = totalRequests / backends.size();
        assertThat(counts.values().stream().map(AtomicLong::get))
                .allSatisfy(count -> assertThat(count).isEqualTo(expected));
    }

    @Test
    @DisplayName("handles cursor overflow past Integer.MAX_VALUE without throwing")
    void survivesCursorOverflow() throws Exception {
        // floorMod, not %, is what makes this work: -1 % 3 == -1 would be an invalid index.
        var cursorField = RoundRobinStrategy.class.getDeclaredField("cursor");
        cursorField.setAccessible(true);
        AtomicInteger cursor = (AtomicInteger) cursorField.get(strategy);
        cursor.set(Integer.MAX_VALUE - 1);

        List<BackendServer> backends = TestBackends.backends(3);
        for (int i = 0; i < 10; i++) {
            assertThat(backends).contains(strategy.selectBackend(backends, TestBackends.context("c")));
        }
        assertThat(cursor.get()).isNegative();
    }

    @Test
    @DisplayName("returns the only backend when the pool has one")
    void handlesSingleBackend() {
        List<BackendServer> single = TestBackends.backends(1);
        assertThat(strategy.selectBackend(single, TestBackends.context("c")).id()).isEqualTo("backend-1");
    }
}
