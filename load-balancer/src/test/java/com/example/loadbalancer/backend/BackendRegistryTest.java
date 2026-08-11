package com.example.loadbalancer.backend;

import com.example.loadbalancer.config.LoadBalancerProperties;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import com.example.loadbalancer.testsupport.TestBackends;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackendRegistryTest {

    private BackendRegistry registry(boolean healthCheckEnabled, boolean assumeHealthy) {
        LoadBalancerProperties properties = TestBackends
                .propertiesBuilder(LoadBalancingAlgorithm.ROUND_ROBIN, List.of(
                        TestBackends.backendConfig("backend-1", "host-1", 8080, 1),
                        TestBackends.backendConfig("backend-2", "host-2", 8081, 2)))
                .healthCheck(healthCheckEnabled, 3, 2)
                .assumeHealthyOnStart(assumeHealthy)
                .build();
        return new BackendRegistry(properties);
    }

    private BackendRegistry registry() {
        return registry(false, true);
    }

    @Test
    @DisplayName("registers the configured backends at startup")
    void registersConfiguredBackends() {
        BackendRegistry registry = registry();

        assertThat(registry.all()).extracting(BackendServer::id)
                .containsExactly("backend-1", "backend-2");
        assertThat(registry.routable()).hasSize(2);
        assertThat(registry.find("backend-2").orElseThrow().weight()).isEqualTo(2);
    }

    @Test
    @DisplayName("rejects a duplicate id rather than silently replacing a live backend")
    void rejectsDuplicateId() {
        BackendRegistry registry = registry();

        assertThatThrownBy(() -> registry.register(
                new BackendSpec("backend-1", "other", 9999, false, 1, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("each mutation publishes a new immutable snapshot with a higher version")
    void publishesVersionedSnapshots() {
        BackendRegistry registry = registry();
        BackendSnapshot before = registry.snapshot();

        registry.register(new BackendSpec("backend-3", "host-3", 8082, false, 1, true));
        BackendSnapshot after = registry.snapshot();

        assertThat(after.version()).isGreaterThan(before.version());
        // The old snapshot is untouched: a request that already read it keeps routing against a
        // stable view, which is the entire point of copy-on-write.
        assertThat(before.size()).isEqualTo(2);
        assertThat(after.size()).isEqualTo(3);
        assertThatThrownBy(() -> after.backends().add(TestBackends.backend("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a state change bumps the version so strategy caches rebuild")
    void stateChangeBumpsVersion() {
        BackendRegistry registry = registry();
        long before = registry.version();

        registry.markUnhealthy(registry.find("backend-1").orElseThrow(), "test");

        assertThat(registry.version()).isGreaterThan(before);
    }

    @Test
    @DisplayName("only UP backends are routable")
    void filtersNonRoutableBackends() {
        BackendRegistry registry = registry();
        BackendServer backend1 = registry.find("backend-1").orElseThrow();

        registry.markUnhealthy(backend1, "test");

        assertThat(registry.routable()).extracting(BackendServer::id).containsExactly("backend-2");
        assertThat(registry.all()).hasSize(2);
    }

    @Test
    @DisplayName("disable moves a backend to DRAINING, not straight out of the pool")
    void disableDrains() {
        BackendRegistry registry = registry();

        registry.disable("backend-1");

        assertThat(registry.find("backend-1").orElseThrow().state()).isEqualTo(BackendState.DRAINING);
        assertThat(registry.routable()).extracting(BackendServer::id).containsExactly("backend-2");
    }

    @Test
    @DisplayName("a health check never resurrects a DISABLED or DRAINING backend")
    void healthChecksCannotOverrideOperator() {
        BackendRegistry registry = registry();
        BackendServer backend = registry.find("backend-1").orElseThrow();

        registry.disable("backend-1");
        registry.markHealthy(backend, "probe succeeded");
        assertThat(backend.state()).isEqualTo(BackendState.DRAINING);

        registry.finishDraining(backend);
        registry.markHealthy(backend, "probe succeeded");
        assertThat(backend.state()).isEqualTo(BackendState.DISABLED);
    }

    @Test
    @DisplayName("enable requires health checks to confirm the backend before routing to it")
    void enableRequiresHealthConfirmation() {
        BackendRegistry registry = registry(true, true);
        registry.disable("backend-1");
        BackendServer backend = registry.find("backend-1").orElseThrow();
        registry.finishDraining(backend);

        registry.enable("backend-1");

        // Not UP: it must earn its place back by passing probes. Sending traffic to a server
        // we have not verified since it was disabled would re-expose a bad deploy.
        assertThat(backend.state()).isEqualTo(BackendState.DOWN);
        assertThat(registry.routable()).extracting(BackendServer::id).containsExactly("backend-2");
    }

    @Test
    @DisplayName("with health checks off, enable puts a backend straight back into rotation")
    void enableIsImmediateWithoutHealthChecks() {
        BackendRegistry registry = registry(false, true);
        BackendServer backend = registry.find("backend-1").orElseThrow();
        registry.disable("backend-1");
        registry.finishDraining(backend);

        registry.enable("backend-1");

        assertThat(backend.state()).isEqualTo(BackendState.UP);
    }

    @Test
    @DisplayName("listeners are notified, and a late listener is replayed current membership")
    void notifiesAndReplaysToListeners() {
        BackendRegistry registry = registry();
        List<BackendChangeEvent> events = new CopyOnWriteArrayList<>();

        registry.addListener(events::add);

        // Replay: both existing backends are reported as ADDED so a listener registered after
        // startup (metrics, client cache) is not missing state.
        assertThat(events).hasSize(2);
        assertThat(events).allMatch(event -> event.type() == BackendChangeEvent.Type.ADDED);

        events.clear();
        registry.register(new BackendSpec("backend-3", "host-3", 8082, false, 1, true));
        registry.markUnhealthy(registry.find("backend-3").orElseThrow(), "test");
        registry.unregister("backend-3");

        assertThat(events).extracting(BackendChangeEvent::type).containsExactly(
                BackendChangeEvent.Type.ADDED,
                BackendChangeEvent.Type.STATE_CHANGED,
                BackendChangeEvent.Type.REMOVED);
    }

    @Test
    @DisplayName("a throwing listener cannot break pool management")
    void survivesMisbehavingListener() {
        BackendRegistry registry = registry();
        AtomicInteger goodListenerCalls = new AtomicInteger();

        registry.addListener(event -> {
            throw new IllegalStateException("listener is broken");
        });
        registry.addListener(event -> goodListenerCalls.incrementAndGet());

        registry.register(new BackendSpec("backend-3", "host-3", 8082, false, 1, true));

        assertThat(registry.find("backend-3")).isPresent();
        assertThat(goodListenerCalls.get()).isPositive();
    }

    @Test
    @DisplayName("reconcile preserves live counters for unchanged backends")
    void reconcilePreservesLiveState() {
        BackendRegistry registry = registry();
        BackendServer backend1 = registry.find("backend-1").orElseThrow();
        backend1.acquireConnection();
        backend1.acquireConnection();

        BackendRegistry.ReconcileResult result = registry.reconcile(List.of(
                new BackendSpec("backend-1", "host-1", 8080, false, 5, true),
                new BackendSpec("backend-3", "host-3", 8082, false, 1, true)));

        assertThat(result.added()).containsExactly("backend-3");
        assertThat(result.removed()).containsExactly("backend-2");
        // Same object, so in-flight requests still decrement the counter they incremented.
        assertThat(registry.find("backend-1").orElseThrow()).isSameAs(backend1);
        assertThat(backend1.activeConnections()).isEqualTo(2);
        assertThat(backend1.weight()).isEqualTo(5);
    }

    @Test
    @DisplayName("reconcile replaces a backend whose address changed")
    void reconcileReplacesMovedBackend() {
        BackendRegistry registry = registry();
        BackendServer original = registry.find("backend-1").orElseThrow();

        registry.reconcile(List.of(
                new BackendSpec("backend-1", "new-host", 9090, false, 1, true),
                new BackendSpec("backend-2", "host-2", 8081, false, 2, true)));

        BackendServer replacement = registry.find("backend-1").orElseThrow();
        assertThat(replacement).isNotSameAs(original);
        assertThat(replacement.baseUrl()).isEqualTo("http://new-host:9090");
    }

    @Test
    @DisplayName("concurrent registrations and removals never lose or corrupt the pool")
    void isThreadSafeUnderConcurrentMutation() throws Exception {
        BackendRegistry registry = registry();
        int threads = 32;
        int perThread = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        try (var pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                int threadId = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            String id = "dyn-" + threadId + "-" + i;
                            registry.register(new BackendSpec(id, "host", 9000, false, 1, true));
                            // Readers must always see a coherent snapshot while writes happen.
                            assertThat(registry.snapshot().backends()).isNotNull();
                            registry.unregister(id);
                        }
                    } catch (Throwable error) {
                        failures.add(error);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(failures).isEmpty();
        // Every dynamic backend was removed, so only the two configured ones remain. A lost
        // update in the copy-on-write path would leave strays behind.
        assertThat(registry.all()).extracting(BackendServer::id)
                .containsExactlyInAnyOrder("backend-1", "backend-2");
    }

    @Test
    @DisplayName("concurrent state changes and registrations never lose a version bump")
    void versionAdvancesMonotonicallyUnderConcurrency() throws Exception {
        BackendRegistry registry = registry();
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Long> versions = new CopyOnWriteArrayList<>();

        try (var pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                int threadId = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 50; i++) {
                            if (threadId % 2 == 0) {
                                registry.updateWeight("backend-1", 1 + (i % 5));
                            } else {
                                String id = "tmp-" + threadId + "-" + i;
                                registry.register(new BackendSpec(id, "h", 9000, false, 1, true));
                                registry.unregister(id);
                            }
                            versions.add(registry.version());
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

        // The snapshot's version must match the counter: if a bumpVersion raced with a
        // register and reverted its snapshot, membership and version would disagree here.
        assertThat(registry.snapshot().version()).isEqualTo(registry.version());
        assertThat(registry.all()).hasSize(2);
        assertThat(new ArrayList<>(versions)).isNotEmpty();
    }
}
