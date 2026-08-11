package com.example.loadbalancer.routing;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.testsupport.TestBackends;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IpHashStrategyTest {

    private final IpHashStrategy strategy = new IpHashStrategy();

    @Test
    @DisplayName("the same client always reaches the same backend")
    void isStableForOneClient() {
        List<BackendServer> backends = TestBackends.backends(3);

        String first = strategy.selectBackend(backends, TestBackends.context("203.0.113.7")).id();
        for (int i = 0; i < 500; i++) {
            assertThat(strategy.selectBackend(backends, TestBackends.context("203.0.113.7")).id())
                    .isEqualTo(first);
        }
    }

    @Test
    @DisplayName("different clients are spread across backends")
    void spreadsDistinctClients() {
        List<BackendServer> backends = TestBackends.backends(3);
        Set<String> chosen = new HashSet<>();

        for (int i = 0; i < 100; i++) {
            chosen.add(strategy.selectBackend(backends, TestBackends.context("10.0.0." + i)).id());
        }

        assertThat(chosen).hasSize(3);
    }

    @Test
    @DisplayName("distributes 10,000 distinct client IPs within ~15% of even")
    void distributesClientsEvenly() {
        List<BackendServer> backends = TestBackends.backends(4);
        Map<String, Integer> counts = new HashMap<>();

        for (int i = 0; i < 10_000; i++) {
            // Sequential addresses across several /16s: adjacent IPs are near-identical
            // strings, which is exactly the input that makes String.hashCode cluster.
            String ip = "10." + (i / 256 % 256) + "." + (i % 256) + "." + (i % 7);
            counts.merge(strategy.selectBackend(backends, TestBackends.context(ip)).id(), 1, Integer::sum);
        }

        assertThat(counts).hasSize(4);
        assertThat(counts.values()).allSatisfy(count -> assertThat(count).isBetween(2100, 2900));
    }

    @Test
    @DisplayName("mapping is deterministic across strategy instances, so ALB replicas agree")
    void isDeterministicAcrossInstances() {
        // Murmur3 is a pure function of the bytes. Anything seeded per JVM would make two
        // ALB replicas disagree, silently breaking affinity as soon as you scale out.
        List<BackendServer> backends = TestBackends.backends(5);
        IpHashStrategy other = new IpHashStrategy();

        for (int i = 0; i < 200; i++) {
            var context = TestBackends.context("192.168.1." + i);
            assertThat(strategy.selectBackend(backends, context).id())
                    .isEqualTo(other.selectBackend(backends, context).id());
        }
    }

    @Test
    @DisplayName("falls back to the request id when no client IP is available")
    void handlesMissingClientIp() {
        List<BackendServer> backends = TestBackends.backends(3);
        LoadBalancingContext noIp = new LoadBalancingContext(
                "req-abc", "GET", "/api/test", null, null, 1, Set.of(), 1L);

        BackendServer chosen = strategy.selectBackend(backends, noIp);

        // Degrades to "stable for this request" rather than throwing or sending every
        // anonymous client to backend-1.
        assertThat(backends).contains(chosen);
        assertThat(strategy.selectBackend(backends, noIp)).isSameAs(chosen);
    }

    @Test
    @DisplayName("pool size changes remap most clients — the documented weakness of modulo hashing")
    void remapsWidelyWhenPoolChanges() {
        List<BackendServer> three = TestBackends.backends(3);
        List<BackendServer> two = three.subList(0, 2);

        int moved = 0;
        for (int i = 0; i < 1000; i++) {
            var context = TestBackends.context("172.16.0." + i);
            if (!strategy.selectBackend(three, context).id().equals(
                    strategy.selectBackend(two, context).id())) {
                moved++;
            }
        }

        // This is why CONSISTENT_HASH exists: with modulo hashing, losing one of three
        // backends moves far more than the third of clients that actually had to move.
        assertThat(moved).isGreaterThan(400);
    }
}
