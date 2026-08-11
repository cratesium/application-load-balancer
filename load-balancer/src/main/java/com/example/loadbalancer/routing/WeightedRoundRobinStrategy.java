package com.example.loadbalancer.routing;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import com.example.loadbalancer.routing.support.PoolDerivedCache;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Weighted round robin using a precomputed <em>smooth</em> schedule.
 *
 * <h2>The three ways to do this, and why this one</h2>
 * <ol>
 *   <li><b>Expand the pool.</b> Put backend A in a list three times and B once, then round
 *       robin. Memory grows with the weights (weight 1000 means a 1000-element list), the
 *       list must be rebuilt on every weight change, and the output is bursty:
 *       {@code A A A B}. Rejected — this is the approach the requirements call out.</li>
 *   <li><b>Modulo the weight sum.</b> Keep a counter, take {@code counter % totalWeight},
 *       and walk the candidates accumulating weights. Lock-free and allocation-free, but
 *       still bursty: a backend with weight 100 receives 100 consecutive requests, which
 *       defeats the purpose of spreading load and produces visible latency waves.</li>
 *   <li><b>Smooth weighted round robin</b> (the algorithm nginx uses). Produces
 *       {@code A A B A} for weights 3:1 instead of {@code A A A B} — the same ratio,
 *       interleaved. Its drawback is that it is inherently stateful: each step mutates a
 *       "current weight" per backend and must be atomic, which in a multithreaded server
 *       means a lock on the hot path.</li>
 * </ol>
 *
 * <p>This implementation takes the output of (3) without the lock of (3). The smooth
 * sequence is fully determined by the weights, so it is computed <em>once</em> per pool
 * shape into an {@code int[]} of backend indexes, and requests simply read
 * {@code schedule[counter++ mod schedule.length]}. Selection is one atomic increment plus
 * an array read: lock-free, allocation-free, O(1), and identical in distribution to nginx.
 * The O(W&times;n) schedule build happens only when membership or a weight changes.
 *
 * <h2>Bounding the schedule</h2>
 * Weights are divided by their GCD first, so 200:100 becomes 2:1 and yields a 3-element
 * schedule rather than a 300-element one. If the reduced total still exceeds
 * {@link #MAX_SCHEDULE_LENGTH}, weights are scaled down proportionally. That trades a tiny
 * amount of ratio precision for a hard memory bound, so a fat-fingered
 * {@code weight: 1000000} cannot allocate a million-element array.
 */
@Component
public class WeightedRoundRobinStrategy implements LoadBalancingStrategy {

    /** Upper bound on the generated schedule; caps both memory and build cost. */
    static final int MAX_SCHEDULE_LENGTH = 4096;

    private final AtomicLong cursor = new AtomicLong();
    private final PoolDerivedCache<int[]> schedules = new PoolDerivedCache<>();

    @Override
    public BackendServer selectBackend(List<BackendServer> healthyBackends, LoadBalancingContext context) {
        int size = healthyBackends.size();
        if (size == 1) {
            return healthyBackends.get(0);
        }
        int[] schedule = schedules.get(healthyBackends, context.poolVersion(),
                WeightedRoundRobinStrategy::buildSchedule);
        int position = (int) Math.floorMod(cursor.getAndIncrement(), schedule.length);
        return healthyBackends.get(schedule[position]);
    }

    @Override
    public LoadBalancingAlgorithm algorithm() {
        return LoadBalancingAlgorithm.WEIGHTED_ROUND_ROBIN;
    }

    /**
     * Generates the smooth weighted round-robin sequence for a candidate list.
     *
     * <p>The classic algorithm: every step adds each backend's weight to its running
     * "current weight", selects the maximum, then subtracts the total weight from the
     * winner. After exactly {@code totalWeight} steps every current weight returns to zero,
     * so the sequence is periodic with that length and each backend appears exactly
     * {@code weight} times per period — with the occurrences spread out rather than
     * clustered.
     *
     * @return array of indexes into {@code candidates}
     */
    static int[] buildSchedule(List<BackendServer> candidates) {
        int size = candidates.size();
        int[] weights = new int[size];
        for (int i = 0; i < size; i++) {
            weights[i] = Math.max(1, candidates.get(i).weight());
        }
        normalise(weights);

        int total = 0;
        for (int weight : weights) {
            total += weight;
        }

        int[] schedule = new int[total];
        int[] current = new int[size];
        for (int step = 0; step < total; step++) {
            int best = 0;
            for (int i = 0; i < size; i++) {
                current[i] += weights[i];
                if (current[i] > current[best]) {
                    best = i;
                }
            }
            schedule[step] = best;
            current[best] -= total;
        }
        return schedule;
    }

    /**
     * Reduces weights by their GCD and, if still too large, scales them into
     * {@link #MAX_SCHEDULE_LENGTH}. Mutates the array in place.
     */
    private static void normalise(int[] weights) {
        int divisor = weights[0];
        for (int weight : weights) {
            divisor = gcd(divisor, weight);
        }
        if (divisor > 1) {
            for (int i = 0; i < weights.length; i++) {
                weights[i] /= divisor;
            }
        }

        long total = 0;
        for (int weight : weights) {
            total += weight;
        }
        if (total <= MAX_SCHEDULE_LENGTH) {
            return;
        }
        // Proportional scale-down. Rounds *down* so the total cannot creep back over the cap,
        // but floors every backend at weight 1 so scaling can never silently drop a backend out
        // of the rotation. Those two rules together bound the schedule at
        // max(MAX_SCHEDULE_LENGTH, backendCount) — the second term only binds for a pool of
        // more than 4096 backends, which is far outside what a single ALB pool holds.
        double factor = (double) MAX_SCHEDULE_LENGTH / total;
        for (int i = 0; i < weights.length; i++) {
            weights[i] = Math.max(1, (int) Math.floor(weights[i] * factor));
        }
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int temp = b;
            b = a % b;
            a = temp;
        }
        return a == 0 ? 1 : a;
    }
}
