package com.example.loadbalancer.routing.hash;

import com.example.loadbalancer.backend.BackendServer;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An immutable consistent-hash ring with virtual nodes.
 *
 * <h2>What consistent hashing buys</h2>
 * With {@code hash(key) mod n}, changing {@code n} remaps almost every key: go from 3
 * backends to 4 and about 75% of clients move. On a ring, each backend owns the arc that
 * ends at its position, so removing a backend moves only the keys in <em>its</em> arc —
 * roughly {@code 1/n} of them — and the rest are untouched. That is the difference between
 * a rolling restart costing one server's worth of cache misses and costing every server's.
 *
 * <h2>Why virtual nodes</h2>
 * With one point per backend, three random positions on the ring produce three wildly
 * unequal arcs; one backend can easily own half the keyspace. Placing each backend at many
 * pseudo-random positions makes the arcs average out — the standard error of a backend's
 * share falls roughly as {@code 1/sqrt(vnodes)}. 100 virtual nodes per backend keeps the
 * imbalance to a few percent, which is why that is the default. Virtual nodes also make
 * removal graceful: a departing backend's many small arcs are absorbed by many different
 * successors instead of dumping its entire load onto one neighbour.
 *
 * <h2>Weights</h2>
 * A backend's virtual node count is multiplied by its weight, so weight 3 owns roughly
 * three times the arc length. Consistent hashing and weighting compose naturally.
 *
 * <h2>Representation</h2>
 * Built into two parallel sorted arrays rather than kept as a {@code TreeMap}. Lookup is a
 * binary search over a contiguous {@code int[]} — cache-friendly, allocation-free, and
 * with none of the pointer chasing of a red-black tree. The ring is immutable once built,
 * so it can be shared across all request threads with no synchronisation whatsoever;
 * membership changes produce a new ring rather than mutating this one.
 */
public final class ConsistentHashRing {

    /** Hard cap on ring points, so a large pool times a large vnode count cannot blow up. */
    private static final int MAX_POINTS = 100_000;

    private final int[] hashes;
    private final BackendServer[] owners;

    private ConsistentHashRing(int[] hashes, BackendServer[] owners) {
        this.hashes = hashes;
        this.owners = owners;
    }

    /**
     * Builds a ring.
     *
     * @param backends            ring members; must be non-empty
     * @param virtualNodesPerUnit virtual nodes per unit of weight
     */
    public static ConsistentHashRing build(List<BackendServer> backends, int virtualNodesPerUnit) {
        if (backends.isEmpty()) {
            throw new IllegalArgumentException("Cannot build a hash ring with no backends");
        }
        int requested = 0;
        for (BackendServer backend : backends) {
            requested += virtualNodesPerUnit * Math.max(1, backend.weight());
        }
        // Scale down uniformly if the requested ring would be excessive, keeping ratios.
        double scale = requested > MAX_POINTS ? (double) MAX_POINTS / requested : 1.0;

        TreeMap<Integer, BackendServer> ring = new TreeMap<>();
        for (BackendServer backend : backends) {
            int points = Math.max(1, (int) Math.round(virtualNodesPerUnit * Math.max(1, backend.weight()) * scale));
            for (int i = 0; i < points; i++) {
                int hash = Murmur3.hash32(backend.id() + "#" + i);
                // On the astronomically rare collision, keep the first owner and move on;
                // one duplicated point shifts a negligible slice of the keyspace.
                ring.putIfAbsent(hash, backend);
            }
        }

        int size = ring.size();
        int[] hashes = new int[size];
        BackendServer[] owners = new BackendServer[size];
        int index = 0;
        for (Map.Entry<Integer, BackendServer> entry : ring.entrySet()) {
            hashes[index] = entry.getKey();
            owners[index] = entry.getValue();
            index++;
        }
        return new ConsistentHashRing(hashes, owners);
    }

    /**
     * Finds the backend that owns {@code key}: the first ring point at or clockwise of the
     * key's hash, wrapping around at the end of the ring.
     */
    public BackendServer locate(String key) {
        int hash = Murmur3.hash32(key);
        int position = Arrays.binarySearch(hashes, hash);
        if (position < 0) {
            position = -(position + 1);
            if (position == hashes.length) {
                position = 0; // wrap past the largest point back to the smallest
            }
        }
        return owners[position];
    }

    /** @return number of points on the ring (virtual nodes, after collisions and scaling). */
    public int points() {
        return hashes.length;
    }
}
