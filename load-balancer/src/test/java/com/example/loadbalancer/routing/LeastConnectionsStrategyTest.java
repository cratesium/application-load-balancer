package com.example.loadbalancer.routing;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.testsupport.TestBackends;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LeastConnectionsStrategyTest {

    private final LeastConnectionsStrategy strategy = new LeastConnectionsStrategy();

    @Test
    @DisplayName("picks the backend with fewest in-flight requests (the requirements' example)")
    void picksLeastLoaded() {
        List<BackendServer> backends = List.of(
                TestBackends.backendWithConnections("cde", 1, 10),
                TestBackends.backendWithConnections("cdf", 1, 3));

        assertThat(strategy.selectBackend(backends, TestBackends.context("c")).id()).isEqualTo("cdf");
    }

    @Test
    @DisplayName("ignores weight — that is weighted least connections' job")
    void ignoresWeight() {
        List<BackendServer> backends = List.of(
                TestBackends.backendWithConnections("strong", 10, 5),
                TestBackends.backendWithConnections("weak", 1, 2));

        assertThat(strategy.selectBackend(backends, TestBackends.context("c")).id()).isEqualTo("weak");
    }

    @Test
    @DisplayName("spreads ties evenly instead of always choosing the first backend")
    void spreadsTiesEvenly() {
        // All idle, so every candidate ties. With a fixed scan start the first backend would
        // take 100% of traffic at low load, which is the failure mode the random start fixes.
        List<BackendServer> backends = TestBackends.backends(3);
        Map<String, Integer> counts = new HashMap<>();

        for (int i = 0; i < 3000; i++) {
            counts.merge(strategy.selectBackend(backends, TestBackends.context("c")).id(), 1, Integer::sum);
        }

        assertThat(counts).hasSize(3);
        assertThat(counts.values()).allSatisfy(count -> assertThat(count).isBetween(850, 1150));
    }

    @Test
    @DisplayName("follows the load as counters change")
    void tracksChangingLoad() {
        BackendServer a = TestBackends.backend("a");
        BackendServer b = TestBackends.backend("b");
        List<BackendServer> backends = List.of(a, b);

        for (int i = 0; i < 5; i++) {
            a.acquireConnection();
        }
        assertThat(strategy.selectBackend(backends, TestBackends.context("c")).id()).isEqualTo("b");

        for (int i = 0; i < 5; i++) {
            a.releaseConnection();
            b.acquireConnection();
        }
        assertThat(strategy.selectBackend(backends, TestBackends.context("c")).id()).isEqualTo("a");
    }

    @Test
    @DisplayName("simulated traffic converges to a balanced in-flight count")
    void convergesUnderSimulatedLoad() {
        // Model a pool where requests arrive faster than they complete: after many rounds the
        // in-flight counts should stay within one of each other, which is the property that
        // makes this algorithm right for variable request durations.
        List<BackendServer> backends = TestBackends.backends(4);

        for (int i = 0; i < 400; i++) {
            strategy.selectBackend(backends, TestBackends.context("c")).acquireConnection();
        }

        int min = backends.stream().mapToInt(BackendServer::activeConnections).min().orElseThrow();
        int max = backends.stream().mapToInt(BackendServer::activeConnections).max().orElseThrow();
        assertThat(max - min).isLessThanOrEqualTo(1);
    }
}
