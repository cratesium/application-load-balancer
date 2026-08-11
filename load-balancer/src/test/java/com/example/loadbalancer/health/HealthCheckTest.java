package com.example.loadbalancer.health;

import com.example.loadbalancer.backend.BackendHealth;
import com.example.loadbalancer.backend.BackendRegistry;
import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.backend.BackendState;
import com.example.loadbalancer.config.LoadBalancerProperties;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import com.example.loadbalancer.testsupport.TestBackends;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Health-check threshold and transition tests.
 *
 * <p>These exercise the hysteresis logic and the registry's transition rules directly rather
 * than standing up an HTTP server. The wire-level behaviour — a real backend going down and the
 * ALB rerouting — is covered in {@code LoadBalancerIntegrationTest}.
 */
class HealthCheckTest {

    @Test
    @DisplayName("takes failure-threshold consecutive failures to signal a demotion")
    void requiresConsecutiveFailures() {
        BackendHealth health = new BackendHealth(3, 2);

        assertThat(health.recordFailure()).isEqualTo(BackendHealth.Signal.NONE);
        assertThat(health.recordFailure()).isEqualTo(BackendHealth.Signal.NONE);
        assertThat(health.recordFailure()).isEqualTo(BackendHealth.Signal.DEMOTE);
    }

    @Test
    @DisplayName("takes success-threshold consecutive successes to signal a promotion")
    void requiresConsecutiveSuccesses() {
        BackendHealth health = new BackendHealth(3, 2);

        assertThat(health.recordSuccess()).isEqualTo(BackendHealth.Signal.NONE);
        assertThat(health.recordSuccess()).isEqualTo(BackendHealth.Signal.PROMOTE);
    }

    @Test
    @DisplayName("a single success resets the failure count — this is what prevents flapping")
    void successResetsFailureCount() {
        BackendHealth health = new BackendHealth(3, 2);

        health.recordFailure();
        health.recordFailure();
        assertThat(health.consecutiveFailures()).isEqualTo(2);

        health.recordSuccess();

        assertThat(health.consecutiveFailures()).isZero();
        // Back to needing three fresh failures, so scattered failures never accumulate into
        // a demotion.
        assertThat(health.recordFailure()).isEqualTo(BackendHealth.Signal.NONE);
        assertThat(health.recordFailure()).isEqualTo(BackendHealth.Signal.NONE);
        assertThat(health.recordFailure()).isEqualTo(BackendHealth.Signal.DEMOTE);
    }

    @Test
    @DisplayName("a single failure resets the success count")
    void failureResetsSuccessCount() {
        BackendHealth health = new BackendHealth(3, 3);

        health.recordSuccess();
        health.recordSuccess();
        health.recordFailure();

        assertThat(health.consecutiveSuccesses()).isZero();
        assertThat(health.recordSuccess()).isEqualTo(BackendHealth.Signal.NONE);
    }

    @Test
    @DisplayName("threshold 1 means a single probe decides, when that is configured")
    void supportsThresholdOfOne() {
        BackendHealth health = new BackendHealth(1, 1);

        assertThat(health.recordFailure()).isEqualTo(BackendHealth.Signal.DEMOTE);
        assertThat(health.recordSuccess()).isEqualTo(BackendHealth.Signal.PROMOTE);
    }

    @Test
    @DisplayName("full cycle: UP -> DOWN -> UP through the registry")
    void fullTransitionCycle() {
        BackendRegistry registry = new BackendRegistry(TestBackends
                .propertiesBuilder(LoadBalancingAlgorithm.ROUND_ROBIN,
                        List.of(TestBackends.backendConfig("backend-1", "host-1", 8080, 1)))
                .healthCheck(true, 3, 2)
                .build());
        BackendServer backend = registry.find("backend-1").orElseThrow();
        BackendHealth health = backend.health();

        assertThat(backend.state()).isEqualTo(BackendState.UP);

        // Two failures: still routable, because one bad probe is not an outage.
        health.recordFailure();
        health.recordFailure();
        assertThat(backend.isRoutable()).isTrue();

        if (health.recordFailure() == BackendHealth.Signal.DEMOTE) {
            registry.markUnhealthy(backend, "three failures");
        }
        assertThat(backend.state()).isEqualTo(BackendState.DOWN);
        assertThat(registry.routable()).isEmpty();

        // One success is not enough to come back.
        health.recordSuccess();
        assertThat(backend.state()).isEqualTo(BackendState.DOWN);

        if (health.recordSuccess() == BackendHealth.Signal.PROMOTE) {
            registry.markHealthy(backend, "two successes");
        }
        assertThat(backend.state()).isEqualTo(BackendState.UP);
        assertThat(registry.routable()).hasSize(1);
    }

    @Test
    @DisplayName("unhealthy backends are never selected for routing")
    void unhealthyBackendsAreNeverSelected() {
        BackendRegistry registry = new BackendRegistry(TestBackends
                .propertiesBuilder(LoadBalancingAlgorithm.ROUND_ROBIN, List.of(
                        TestBackends.backendConfig("backend-1", "host-1", 8080, 1),
                        TestBackends.backendConfig("backend-2", "host-2", 8081, 1),
                        TestBackends.backendConfig("backend-3", "host-3", 8082, 1)))
                .build());

        registry.markUnhealthy(registry.find("backend-2").orElseThrow(), "test");

        for (int i = 0; i < 100; i++) {
            assertThat(registry.routable()).extracting(BackendServer::id).doesNotContain("backend-2");
        }
    }

    @Test
    @DisplayName("passive health demotes a backend after enough real failures in the window")
    void passiveHealthDemotesOnRepeatedFailures() {
        AtomicLong clock = new AtomicLong();
        BackendRegistry registry = registryWithPassive(3, Duration.ofSeconds(10));
        PassiveHealthMonitor monitor = new PassiveHealthMonitor(
                propertiesWithPassive(3, Duration.ofSeconds(10)), registry, clock::get);
        BackendServer backend = registry.find("backend-1").orElseThrow();

        monitor.recordFailure(backend, "CONNECTION_REFUSED");
        monitor.recordFailure(backend, "CONNECTION_REFUSED");
        assertThat(backend.state()).isEqualTo(BackendState.UP);

        monitor.recordFailure(backend, "CONNECTION_REFUSED");

        assertThat(backend.state()).isEqualTo(BackendState.DOWN);
    }

    @Test
    @DisplayName("passive failures spread beyond the window never accumulate into a demotion")
    void passiveHealthWindowExpires() {
        AtomicLong clock = new AtomicLong();
        BackendRegistry registry = registryWithPassive(3, Duration.ofSeconds(10));
        PassiveHealthMonitor monitor = new PassiveHealthMonitor(
                propertiesWithPassive(3, Duration.ofSeconds(10)), registry, clock::get);
        BackendServer backend = registry.find("backend-1").orElseThrow();

        for (int i = 0; i < 10; i++) {
            monitor.recordFailure(backend, "TIMEOUT");
            // One failure every 11 seconds is background noise, not an outage.
            clock.addAndGet(Duration.ofSeconds(11).toNanos());
        }

        assertThat(backend.state()).isEqualTo(BackendState.UP);
    }

    @Test
    @DisplayName("a successful request clears accumulated passive failures")
    void passiveSuccessResetsCount() {
        AtomicLong clock = new AtomicLong();
        BackendRegistry registry = registryWithPassive(3, Duration.ofSeconds(10));
        PassiveHealthMonitor monitor = new PassiveHealthMonitor(
                propertiesWithPassive(3, Duration.ofSeconds(10)), registry, clock::get);
        BackendServer backend = registry.find("backend-1").orElseThrow();

        monitor.recordFailure(backend, "TIMEOUT");
        monitor.recordFailure(backend, "TIMEOUT");
        monitor.recordSuccess(backend);
        assertThat(monitor.failureCount("backend-1")).isZero();

        monitor.recordFailure(backend, "TIMEOUT");
        monitor.recordFailure(backend, "TIMEOUT");

        assertThat(backend.state()).isEqualTo(BackendState.UP);
    }

    @Test
    @DisplayName("passive health only ever demotes; recovery is the active checker's job")
    void passiveHealthNeverPromotes() {
        AtomicLong clock = new AtomicLong();
        BackendRegistry registry = registryWithPassive(3, Duration.ofSeconds(10));
        PassiveHealthMonitor monitor = new PassiveHealthMonitor(
                propertiesWithPassive(3, Duration.ofSeconds(10)), registry, clock::get);
        BackendServer backend = registry.find("backend-1").orElseThrow();
        registry.markUnhealthy(backend, "test");

        for (int i = 0; i < 100; i++) {
            monitor.recordSuccess(backend);
        }

        // A DOWN backend receives no traffic, so there is no passive evidence to recover from.
        // Promotion stays with the active checker; this asymmetry stops oscillation.
        assertThat(backend.state()).isEqualTo(BackendState.DOWN);
    }

    private LoadBalancerProperties propertiesWithPassive(int threshold, Duration window) {
        return TestBackends.propertiesBuilder(LoadBalancingAlgorithm.ROUND_ROBIN,
                        List.of(TestBackends.backendConfig("backend-1", "host-1", 8080, 1)))
                .passiveHealth(true, threshold, window)
                .build();
    }

    private BackendRegistry registryWithPassive(int threshold, Duration window) {
        return new BackendRegistry(propertiesWithPassive(threshold, window));
    }
}
