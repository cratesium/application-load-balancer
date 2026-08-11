package com.example.loadbalancer.routing;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.config.LoadBalancerProperties;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import com.example.loadbalancer.routing.hash.ConsistentHashRing;
import com.example.loadbalancer.routing.support.PoolDerivedCache;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Session affinity that survives pool changes, via a virtual-node hash ring.
 *
 * <p>Same purpose as {@link IpHashStrategy} — the same client keeps hitting the same
 * backend — but with a far better failure story. Modulo hashing remaps essentially every
 * client whenever the pool size changes; the ring remaps only the clients that belonged to
 * the departed backend. With three backends, losing one moves about a third of clients
 * instead of all of them, so a rolling deploy or a single health-check blip does not
 * invalidate every warm cache at once.
 *
 * <p>The ring is rebuilt only when membership, weights or health change (detected via the
 * registry version carried on the context) and is cached by
 * {@link PoolDerivedCache}. Building it is O(vnodes) hashing work; looking a key up is a
 * binary search. Per request, this costs one hash and one binary search over an immutable
 * array — no locks, no allocation.
 *
 * @see ConsistentHashRing for why virtual nodes are needed and how weights map onto arcs
 */
@Component
public class ConsistentHashStrategy implements LoadBalancingStrategy {

    private final int virtualNodes;
    private final PoolDerivedCache<ConsistentHashRing> rings = new PoolDerivedCache<>();

    public ConsistentHashStrategy(LoadBalancerProperties properties) {
        this.virtualNodes = properties.consistentHash().virtualNodes();
    }

    @Override
    public BackendServer selectBackend(List<BackendServer> healthyBackends, LoadBalancingContext context) {
        if (healthyBackends.size() == 1) {
            return healthyBackends.get(0);
        }
        ConsistentHashRing ring = rings.get(healthyBackends, context.poolVersion(),
                candidates -> ConsistentHashRing.build(candidates, virtualNodes));
        return ring.locate(context.resolvedAffinityKey());
    }

    @Override
    public LoadBalancingAlgorithm algorithm() {
        return LoadBalancingAlgorithm.CONSISTENT_HASH;
    }
}
