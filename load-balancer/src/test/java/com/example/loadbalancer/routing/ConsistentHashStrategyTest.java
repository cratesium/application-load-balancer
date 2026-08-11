package com.example.loadbalancer.routing;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import com.example.loadbalancer.routing.hash.ConsistentHashRing;
import com.example.loadbalancer.testsupport.TestBackends;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConsistentHashStrategyTest {

    private final ConsistentHashStrategy strategy = new ConsistentHashStrategy(
            TestBackends.propertiesBuilder(LoadBalancingAlgorithm.CONSISTENT_HASH,
                    List.of(TestBackends.backendConfig("backend-1", "h1", 8080, 1))).build());

    @Test
    @DisplayName("the same key always maps to the same backend")
    void isStableForOneKey() {
        List<BackendServer> backends = TestBackends.backends(3);

        String first = strategy.selectBackend(backends, TestBackends.context("user-42")).id();
        for (int i = 0; i < 200; i++) {
            assertThat(strategy.selectBackend(backends, TestBackends.context("user-42")).id())
                    .isEqualTo(first);
        }
    }

    @Test
    @DisplayName("removing one of three backends moves only about a third of clients")
    void minimisesReassignmentOnRemoval() {
        List<BackendServer> three = TestBackends.backends(3);
        List<BackendServer> two = List.of(three.get(0), three.get(1));

        int clients = 10_000;
        int moved = 0;
        int movedFromSurvivors = 0;

        for (int i = 0; i < clients; i++) {
            var context = TestBackends.context("client-" + i);
            String before = strategy.selectBackend(three, context).id();
            String after = strategy.selectBackend(two, context).id();
            if (!before.equals(after)) {
                moved++;
                if (!before.equals("backend-3")) {
                    movedFromSurvivors++;
                }
            }
        }

        // Only the departed backend's share should move. Modulo hashing would move ~2/3 here.
        double movedFraction = (double) moved / clients;
        assertThat(movedFraction).isBetween(0.25, 0.42);

        // The crucial property: clients of the surviving backends are untouched.
        assertThat(movedFromSurvivors).isZero();
    }

    @Test
    @DisplayName("adding a fourth backend moves only about a quarter of clients")
    void minimisesReassignmentOnAddition() {
        List<BackendServer> three = TestBackends.backends(3);
        List<BackendServer> four = new ArrayList<>(three);
        four.add(TestBackends.backend("backend-4"));

        int clients = 10_000;
        int moved = 0;
        for (int i = 0; i < clients; i++) {
            var context = TestBackends.context("client-" + i);
            if (!strategy.selectBackend(three, context).id()
                    .equals(strategy.selectBackend(four, context).id())) {
                moved++;
            }
        }

        double movedFraction = (double) moved / clients;
        assertThat(movedFraction).isBetween(0.18, 0.34);
    }

    @Test
    @DisplayName("virtual nodes keep the distribution within ~20% of even")
    void distributesEvenlyThanksToVirtualNodes() {
        List<BackendServer> backends = TestBackends.backends(4);
        Map<String, Integer> counts = new HashMap<>();

        for (int i = 0; i < 20_000; i++) {
            counts.merge(strategy.selectBackend(backends, TestBackends.context("key-" + i)).id(),
                    1, Integer::sum);
        }

        assertThat(counts).hasSize(4);
        // With 100 vnodes per backend the standard error is a few percent; without virtual
        // nodes at all, one backend could easily own half the keyspace.
        assertThat(counts.values()).allSatisfy(count -> assertThat(count).isBetween(4000, 6000));
    }

    @Test
    @DisplayName("weights scale a backend's share of the ring")
    void respectsWeights() {
        List<BackendServer> backends = List.of(
                TestBackends.backend("heavy", 3),
                TestBackends.backend("light", 1));
        Map<String, Integer> counts = new HashMap<>();

        for (int i = 0; i < 20_000; i++) {
            counts.merge(strategy.selectBackend(backends, TestBackends.context("key-" + i)).id(),
                    1, Integer::sum);
        }

        double heavyShare = counts.get("heavy") / 20_000.0;
        assertThat(heavyShare).isBetween(0.68, 0.82);
    }

    @Test
    @DisplayName("the ring is built once per pool shape, not per request")
    void cachesRingPerPoolVersion() {
        List<BackendServer> backends = TestBackends.backends(3);
        ConsistentHashRing first = ConsistentHashRing.build(backends, 100);

        // A ring for three weight-1 backends has ~300 points, minus any hash collisions.
        assertThat(first.points()).isBetween(295, 300);

        // Same pool and version many times over: the strategy must stay fast and consistent.
        for (int i = 0; i < 5000; i++) {
            strategy.selectBackend(backends, TestBackends.context("key-" + i, 7L));
        }
        assertThat(strategy.selectBackend(backends, TestBackends.context("key-1", 7L)).id())
                .isEqualTo(strategy.selectBackend(backends, TestBackends.context("key-1", 7L)).id());
    }

    @Test
    @DisplayName("ring lookup wraps around past the largest point")
    void wrapsAroundTheRing() {
        // Every key must find an owner, including keys hashing above the highest ring point.
        List<BackendServer> backends = TestBackends.backends(2);
        ConsistentHashRing ring = ConsistentHashRing.build(backends, 8);

        for (int i = 0; i < 5000; i++) {
            assertThat(ring.locate("key-" + i)).isNotNull();
        }
    }

    @Test
    @DisplayName("mapping is identical across instances, so ALB replicas agree")
    void isDeterministicAcrossInstances() {
        List<BackendServer> backends = TestBackends.backends(4);
        ConsistentHashRing a = ConsistentHashRing.build(backends, 100);
        ConsistentHashRing b = ConsistentHashRing.build(backends, 100);

        for (int i = 0; i < 500; i++) {
            assertThat(a.locate("key-" + i).id()).isEqualTo(b.locate("key-" + i).id());
        }
    }
}
