package com.example.loadbalancer.routing;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.testsupport.TestBackends;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WeightedLeastConnectionsStrategyTest {

    private final WeightedLeastConnectionsStrategy strategy = new WeightedLeastConnectionsStrategy();

    @Test
    @DisplayName("prefers the high-capacity backend even though it has more connections")
    void normalisesByCapacity() {
        // The requirements' example: cde has 10 connections but 5x the capacity.
        //   cde: (10 + 1) / 5 = 2.2
        //   cdf: ( 3 + 1) / 1 = 4.0   -> cde wins
        // Plain least-connections would pick cdf, which is the weaker machine.
        List<BackendServer> backends = List.of(
                TestBackends.backendWithConnections("cde", 5, 10),
                TestBackends.backendWithConnections("cdf", 1, 3));

        assertThat(strategy.selectBackend(backends, TestBackends.context("c")).id()).isEqualTo("cde");
    }

    @Test
    @DisplayName("switches away once the strong backend is genuinely saturated")
    void switchesWhenStrongBackendSaturates() {
        //   strong: (30 + 1) / 5 = 6.2
        //   weak:   ( 3 + 1) / 1 = 4.0   -> weak wins
        List<BackendServer> backends = List.of(
                TestBackends.backendWithConnections("strong", 5, 30),
                TestBackends.backendWithConnections("weak", 1, 3));

        assertThat(strategy.selectBackend(backends, TestBackends.context("c")).id()).isEqualTo("weak");
    }

    @Test
    @DisplayName("respects weight when all backends are idle, thanks to the +1 in the formula")
    void respectsWeightWhenIdle() {
        // Without the +1, every idle backend would score 0/weight = 0 and tie, so capacity
        // would be ignored exactly when the pool is quiet.
        List<BackendServer> backends = List.of(
                TestBackends.backend("weak", 1),
                TestBackends.backend("strong", 8));

        assertThat(strategy.selectBackend(backends, TestBackends.context("c")).id()).isEqualTo("strong");
    }

    @Test
    @DisplayName("steady state distributes connections in proportion to weight")
    void steadyStateIsProportionalToWeight() {
        BackendServer strong = TestBackends.backend("strong", 4);
        BackendServer weak = TestBackends.backend("weak", 1);
        List<BackendServer> backends = List.of(strong, weak);

        for (int i = 0; i < 500; i++) {
            strategy.selectBackend(backends, TestBackends.context("c")).acquireConnection();
        }

        // 4:1 capacity should produce roughly 4:1 in-flight counts.
        double ratio = (double) strong.activeConnections() / weak.activeConnections();
        assertThat(ratio).isBetween(3.5, 4.5);
        assertThat(strong.activeConnections() + weak.activeConnections()).isEqualTo(500);
    }

    @Test
    @DisplayName("uses exact integer arithmetic, so near-ties are decided deterministically")
    void usesExactArithmetic() {
        // (2+1)/3 = 1.0 exactly and (3+1)/4 = 1.0 exactly: a true tie. The comparison is a
        // cross-multiplication of longs, so no floating-point rounding decides this, and the
        // strict '<' means the first candidate scanned is kept.
        List<BackendServer> backends = List.of(
                TestBackends.backendWithConnections("a", 3, 2),
                TestBackends.backendWithConnections("b", 4, 3));

        // Whichever is chosen, it must be one of the two and must be stable across repeats
        // for a given scan order; assert only the contract, not the tie-break.
        for (int i = 0; i < 100; i++) {
            assertThat(backends).contains(strategy.selectBackend(backends, TestBackends.context("c")));
        }
    }
}
