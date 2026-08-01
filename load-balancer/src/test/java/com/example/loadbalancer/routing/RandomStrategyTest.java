package com.example.loadbalancer.routing;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.testsupport.TestBackends;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RandomStrategyTest {

    private final RandomStrategy strategy = new RandomStrategy();

    @Test
    @DisplayName("distributes 10,000 requests roughly evenly across three backends")
    void distributesRoughlyEvenly() {
        List<BackendServer> backends = TestBackends.backends(3);
        Map<String, Integer> counts = new HashMap<>();

        for (int i = 0; i < 10_000; i++) {
            counts.merge(strategy.selectBackend(backends, TestBackends.context("c")).id(), 1, Integer::sum);
        }

        assertThat(counts).hasSize(3);
        // Uniform random over 10,000 draws: the 3-sigma band around 3,333 is about +/-150,
        // so a 10% tolerance is comfortably wide enough not to be flaky while still catching
        // a genuinely skewed generator.
        assertThat(counts.values()).allSatisfy(count -> assertThat(count).isBetween(3000, 3670));
    }

    @Test
    @DisplayName("eventually selects every backend in the pool")
    void reachesEveryBackend() {
        List<BackendServer> backends = TestBackends.backends(5);
        Map<String, Integer> counts = new HashMap<>();

        for (int i = 0; i < 500; i++) {
            counts.merge(strategy.selectBackend(backends, TestBackends.context("c")).id(), 1, Integer::sum);
        }

        assertThat(counts).hasSize(5);
    }

    @Test
    @DisplayName("stays uniform under concurrency: ThreadLocalRandom has no shared seed")
    void isUniformUnderConcurrency() throws Exception {
        int threads = 50;
        int perThread = 400;
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
                            counts.get(strategy.selectBackend(backends, TestBackends.context("c")).id())
                                    .incrementAndGet();
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

        long total = counts.values().stream().mapToLong(AtomicLong::get).sum();
        assertThat(total).isEqualTo((long) threads * perThread);
        long expected = total / backends.size();
        assertThat(counts.values().stream().map(AtomicLong::get)).allSatisfy(count ->
                assertThat(count).isBetween((long) (expected * 0.85), (long) (expected * 1.15)));
    }

    @Test
    @DisplayName("returns the only backend when the pool has one")
    void handlesSingleBackend() {
        List<BackendServer> single = TestBackends.backends(1);
        assertThat(strategy.selectBackend(single, TestBackends.context("c")).id()).isEqualTo("backend-1");
    }
}
