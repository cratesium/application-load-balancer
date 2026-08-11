package com.example.loadbalancer.routing;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Uniform random selection.
 *
 * <p>Uses {@link ThreadLocalRandom} rather than a shared {@link java.util.Random}: a shared
 * {@code Random} has a single {@code AtomicLong} seed that every thread CASes on every
 * call, making it a contention point precisely when traffic is highest.
 * {@code ThreadLocalRandom} keeps per-thread state, so there is no sharing at all.
 * {@code Math.random()} is the same shared-instance trap and is equally unsuitable.
 *
 * <p>Random has no memory, so it needs no rebalancing when the pool changes and cannot
 * develop the lock-step convoy behaviour that synchronised round robin can. The cost is
 * variance: with a small number of requests the split is visibly uneven, and it converges
 * to uniform only over thousands of requests.
 */
@Component
public class RandomStrategy implements LoadBalancingStrategy {

    @Override
    public BackendServer selectBackend(List<BackendServer> healthyBackends, LoadBalancingContext context) {
        int size = healthyBackends.size();
        if (size == 1) {
            return healthyBackends.get(0);
        }
        return healthyBackends.get(ThreadLocalRandom.current().nextInt(size));
    }

    @Override
    public LoadBalancingAlgorithm algorithm() {
        return LoadBalancingAlgorithm.RANDOM;
    }
}
