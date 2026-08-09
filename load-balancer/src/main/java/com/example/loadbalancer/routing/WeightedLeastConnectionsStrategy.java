package com.example.loadbalancer.routing;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Least connections, normalised by capacity.
 *
 * <h2>The formula</h2>
 * Each candidate is scored as
 *
 * <pre>
 *   load(b) = (activeConnections(b) + 1) / weight(b)
 * </pre>
 *
 * and the lowest score wins.
 *
 * <p><b>Why {@code +1}.</b> Without it an idle backend scores 0 regardless of weight, so
 * every idle backend ties and capacity is ignored exactly when the pool is quiet. The
 * {@code +1} means "score this backend as if it had already accepted the request we are
 * about to send", which is what we actually want to compare.
 *
 * <p><b>Why division by weight.</b> Weight represents relative capacity. A backend with
 * weight 5 and 10 in-flight requests is at 2.0 units of load; one with weight 1 and 3
 * in-flight is at 4.0. Raw least-connections would pick the second — the weaker machine —
 * because 3 &lt; 10. Normalising picks the first, which is correct: it has twice the
 * headroom.
 *
 * <p>Worked through the example from the requirements:
 * <pre>
 *   cde: weight 5, 10 active -> (10 + 1) / 5 = 2.2
 *   cdf: weight 1,  3 active -> ( 3 + 1) / 1 = 4.0   -> cde wins
 * </pre>
 *
 * <p><b>Why integers.</b> The comparison {@code (a1+1)/w1 < (a2+1)/w2} is evaluated as the
 * cross-multiplication {@code (a1+1) * w2 < (a2+1) * w1}. Both sides are {@code long}, so
 * the result is exact — no floating-point rounding decides which server gets traffic, and
 * ties are genuine ties rather than artefacts of binary representation. Weights are
 * validated {@code >= 1} at configuration time, so there is no division by zero to guard.
 *
 * <p>Steady-state behaviour: traffic distributes so that each backend's in-flight count is
 * proportional to its weight — which is the property you actually want from "weighted",
 * and which weighted round robin only achieves if every request costs the same.
 */
@Component
public class WeightedLeastConnectionsStrategy implements LoadBalancingStrategy {

    @Override
    public BackendServer selectBackend(List<BackendServer> healthyBackends, LoadBalancingContext context) {
        int size = healthyBackends.size();
        if (size == 1) {
            return healthyBackends.get(0);
        }

        int start = ThreadLocalRandom.current().nextInt(size);
        BackendServer best = healthyBackends.get(start);
        long bestLoad = best.activeConnections() + 1L;
        long bestWeight = Math.max(1, best.weight());

        for (int offset = 1; offset < size; offset++) {
            BackendServer candidate = healthyBackends.get((start + offset) % size);
            long load = candidate.activeConnections() + 1L;
            long weight = Math.max(1, candidate.weight());
            // load/weight < bestLoad/bestWeight, without floating point.
            if (load * bestWeight < bestLoad * weight) {
                best = candidate;
                bestLoad = load;
                bestWeight = weight;
            }
        }
        return best;
    }

    @Override
    public LoadBalancingAlgorithm algorithm() {
        return LoadBalancingAlgorithm.WEIGHTED_LEAST_CONNECTIONS;
    }
}
