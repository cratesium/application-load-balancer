package com.example.loadbalancer.routing;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.testsupport.TestBackends;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WeightedRoundRobinStrategyTest {

    private final WeightedRoundRobinStrategy strategy = new WeightedRoundRobinStrategy();

    @Test
    @DisplayName("splits traffic 75/25 for weights 3:1")
    void respectsWeightRatio() {
        List<BackendServer> backends = List.of(
                TestBackends.backend("cde", 3),
                TestBackends.backend("cdf", 1));

        Map<String, Integer> counts = count(backends, 10_000);

        // The schedule is periodic with length 4, so 10,000 requests divide exactly.
        assertThat(counts.get("cde")).isEqualTo(7500);
        assertThat(counts.get("cdf")).isEqualTo(2500);
    }

    @Test
    @DisplayName("interleaves rather than bursting: 3:1 gives A A B A, not A A A B")
    void producesSmoothSequence() {
        List<BackendServer> backends = List.of(
                TestBackends.backend("a", 3),
                TestBackends.backend("b", 1));

        List<String> sequence = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            sequence.add(strategy.selectBackend(backends, TestBackends.context("c")).id());
        }

        // Smooth WRR (nginx's algorithm) yields a,a,b,a per period. The naive "repeat each
        // backend weight times" approach would yield a,a,a,b — the same ratio, but with the
        // low-weight backend starved for three consecutive requests every cycle.
        assertThat(sequence).containsExactly("a", "a", "b", "a", "a", "a", "b", "a");
        assertThat(sequence.subList(0, 4)).containsExactly("a", "a", "b", "a");
    }

    @Test
    @DisplayName("reduces weights by their GCD so 200:100 costs 3 slots, not 300")
    void reducesByGcd() {
        List<BackendServer> backends = List.of(
                TestBackends.backend("a", 200),
                TestBackends.backend("b", 100));

        int[] schedule = WeightedRoundRobinStrategy.buildSchedule(backends);

        assertThat(schedule).hasSize(3);
        Map<String, Integer> counts = count(backends, 3000);
        assertThat(counts.get("a")).isEqualTo(2000);
        assertThat(counts.get("b")).isEqualTo(1000);
    }

    @Test
    @DisplayName("caps the schedule length for absurd weights instead of allocating unbounded")
    void capsScheduleLength() {
        List<BackendServer> backends = List.of(
                TestBackends.backend("a", 1_000_000),
                TestBackends.backend("b", 7));

        int[] schedule = WeightedRoundRobinStrategy.buildSchedule(backends);

        assertThat(schedule.length).isLessThanOrEqualTo(WeightedRoundRobinStrategy.MAX_SCHEDULE_LENGTH);
        // Even after scaling, the low-weight backend keeps at least one slot: scaling must
        // never silently remove a backend from the rotation.
        assertThat(schedule).contains(1);
    }

    @Test
    @DisplayName("behaves as plain round robin when all weights are equal")
    void equalWeightsBehaveLikeRoundRobin() {
        List<BackendServer> backends = TestBackends.backends(3);

        Map<String, Integer> counts = count(backends, 3000);

        assertThat(counts.values()).allSatisfy(count -> assertThat(count).isEqualTo(1000));
    }

    @Test
    @DisplayName("rebuilds the schedule when a weight changes")
    void picksUpWeightChanges() {
        BackendServer a = TestBackends.backend("a", 1);
        BackendServer b = TestBackends.backend("b", 1);
        List<BackendServer> backends = List.of(a, b);

        Map<String, Integer> before = count(backends, 1000, 1L);
        assertThat(before.get("a")).isEqualTo(500);

        a.weight(9);
        // The registry bumps its version on a weight change; that is what invalidates the
        // cached schedule. Same pool, new version.
        Map<String, Integer> after = count(backends, 1000, 2L);

        assertThat(after.get("a")).isEqualTo(900);
        assertThat(after.get("b")).isEqualTo(100);
    }

    @Test
    @DisplayName("adapts when a backend leaves the candidate set")
    void adaptsToShrinkingPool() {
        BackendServer a = TestBackends.backend("a", 3);
        BackendServer b = TestBackends.backend("b", 1);

        Map<String, Integer> counts = count(List.of(a), 100, 1L);
        assertThat(counts.get("a")).isEqualTo(100);

        counts = count(List.of(a, b), 1000, 1L);
        assertThat(counts.get("b")).isEqualTo(250);
    }

    private Map<String, Integer> count(List<BackendServer> backends, int requests) {
        return count(backends, requests, 1L);
    }

    private Map<String, Integer> count(List<BackendServer> backends, int requests, long poolVersion) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < requests; i++) {
            String id = strategy.selectBackend(backends, TestBackends.context("c", poolVersion)).id();
            counts.merge(id, 1, Integer::sum);
        }
        return counts;
    }
}
