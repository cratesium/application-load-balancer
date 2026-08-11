package com.example.loadbalancer.routing;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plain round robin: hand out backends in rotation.
 *
 * <h2>Concurrency</h2>
 * The cursor is a single {@link AtomicInteger} advanced with {@code getAndIncrement}, a
 * lock-free CAS on one cache line. A plain {@code int} would lose increments under
 * concurrent access and skew the distribution; a {@code synchronized} counter would
 * serialise every event-loop thread through one monitor on the hottest path in the
 * application. The CAS may spin briefly under extreme contention, but it never parks a
 * thread and never blocks an event loop.
 *
 * <p>{@code getAndIncrement} is allowed to overflow into negative numbers. That is handled
 * with {@link Math#floorMod} rather than {@code %} — {@code -1 % 3} is {@code -1} in Java
 * and would throw {@code IndexOutOfBoundsException} roughly two billion requests in, which
 * is exactly the kind of bug that only ever appears in production.
 *
 * <h2>Behaviour when the pool changes</h2>
 * The cursor is not rebased when the candidate list shrinks or grows, so a membership
 * change perturbs the rotation once. Perfect fairness across a resize would require
 * per-pool cursors and is not worth the bookkeeping: round robin's contract is even
 * distribution over time, not a specific sequence.
 */
@Component
public class RoundRobinStrategy implements LoadBalancingStrategy {

    private final AtomicInteger cursor = new AtomicInteger();

    @Override
    public BackendServer selectBackend(List<BackendServer> healthyBackends, LoadBalancingContext context) {
        int size = healthyBackends.size();
        if (size == 1) {
            return healthyBackends.get(0);
        }
        int index = Math.floorMod(cursor.getAndIncrement(), size);
        return healthyBackends.get(index);
    }

    @Override
    public LoadBalancingAlgorithm algorithm() {
        return LoadBalancingAlgorithm.ROUND_ROBIN;
    }
}
