package com.example.loadbalancer.routing;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sends each request to the backend with the fewest in-flight requests.
 *
 * <p>Unlike round robin, this reacts to what is actually happening: if one backend is
 * running slow — GC pause, cold cache, a noisy neighbour — its in-flight count rises and it
 * stops attracting new work automatically. That makes it the right default whenever request
 * durations vary, which in practice is almost always.
 *
 * <h2>Why a plain scan, and why the random start</h2>
 * The counters are read from atomics that other threads are mutating, so the scan sees a
 * slightly stale view. That is fine and unavoidable: taking a consistent snapshot would
 * require locking every backend, and the value would be stale by the time the request
 * reached the wire anyway.
 *
 * <p>The scan starts at a random offset instead of index 0. With a fixed start, every tie
 * resolves to the earliest backend in the list, so at low load — when all counts are 0 —
 * the first backend receives everything. Rotating the start distributes ties evenly, which
 * also breaks the "thundering herd" pattern where many threads observing the same idle
 * backend all pick it in the same instant.
 *
 * <p>Cost is O(n) per request with no allocation. For the tens-to-hundreds of backends a
 * single ALB pool realistically holds, that is cheaper than maintaining a concurrent sorted
 * structure whose invariants would have to be re-established on every increment.
 */
@Component
public class LeastConnectionsStrategy implements LoadBalancingStrategy {

    @Override
    public BackendServer selectBackend(List<BackendServer> healthyBackends, LoadBalancingContext context) {
        int size = healthyBackends.size();
        if (size == 1) {
            return healthyBackends.get(0);
        }

        int start = ThreadLocalRandom.current().nextInt(size);
        BackendServer best = null;
        int bestConnections = Integer.MAX_VALUE;

        for (int offset = 0; offset < size; offset++) {
            BackendServer candidate = healthyBackends.get((start + offset) % size);
            int active = candidate.activeConnections();
            if (active < bestConnections) {
                best = candidate;
                bestConnections = active;
                if (active == 0) {
                    // Cannot do better than idle; stop early.
                    break;
                }
            }
        }
        return best;
    }

    @Override
    public LoadBalancingAlgorithm algorithm() {
        return LoadBalancingAlgorithm.LEAST_CONNECTIONS;
    }
}
