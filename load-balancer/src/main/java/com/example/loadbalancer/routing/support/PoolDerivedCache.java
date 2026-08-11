package com.example.loadbalancer.routing.support;

import com.example.loadbalancer.backend.BackendServer;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Caches a structure derived from a candidate set — a weighted schedule, a hash ring —
 * and rebuilds it only when that set changes.
 *
 * <h2>Why this exists</h2>
 * Weighted round-robin and consistent hashing need preprocessing that is far too expensive
 * to redo per request (building a 100-virtual-node ring for 10 backends is 1,000 hashes and
 * a {@code TreeMap}). But the candidate set is not constant: it shrinks when a backend goes
 * DOWN, differs per route, and differs between retry attempts. So the cache is keyed by the
 * <em>actual candidate list</em>, not just by a registry version.
 *
 * <h2>Correctness</h2>
 * A cache hit is confirmed by comparing the registry version <em>and</em> every member by
 * reference identity. Reference identity is sound because {@code BackendRegistry} keeps one
 * stable {@code BackendServer} instance per backend for its whole lifetime. A hash collision
 * therefore cannot return a schedule for the wrong pool — it can only cause a rebuild.
 *
 * <p>Validation is O(n) with zero allocation, which is the same order as the per-request
 * scan every other strategy does anyway.
 *
 * @param <T> the derived structure
 */
public final class PoolDerivedCache<T> {

    /** Bound on distinct candidate sets held; prevents unbounded growth from route churn. */
    private static final int MAX_ENTRIES = 64;

    private final ConcurrentHashMap<Integer, Entry<T>> entries = new ConcurrentHashMap<>();

    private record Entry<T>(BackendServer[] members, long version, T value) {
    }

    /**
     * Returns the derived structure for {@code candidates}, building it if absent or stale.
     *
     * @param candidates the eligible backends, in a stable order
     * @param version    the registry version the candidates were derived from
     * @param builder    invoked on a miss; must be a pure function of the candidate list
     */
    public T get(List<BackendServer> candidates, long version, Function<List<BackendServer>, T> builder) {
        int key = fingerprint(candidates, version);
        Entry<T> existing = entries.get(key);
        if (existing != null && matches(existing, candidates, version)) {
            return existing.value();
        }
        if (entries.size() >= MAX_ENTRIES) {
            // Route churn or a long tail of pool shapes; drop everything rather than
            // implementing an eviction policy for a cache that is cheap to refill.
            entries.clear();
        }
        Entry<T> fresh = new Entry<>(candidates.toArray(new BackendServer[0]), version, builder.apply(candidates));
        entries.put(key, fresh);
        return fresh.value();
    }

    /** Test/administrative hook: forces the next lookup to rebuild. */
    public void invalidate() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }

    private static int fingerprint(List<BackendServer> candidates, long version) {
        int hash = Long.hashCode(version);
        for (int i = 0; i < candidates.size(); i++) {
            hash = hash * 31 + candidates.get(i).id().hashCode();
        }
        return hash;
    }

    private static <T> boolean matches(Entry<T> entry, List<BackendServer> candidates, long version) {
        if (entry.version() != version || entry.members().length != candidates.size()) {
            return false;
        }
        BackendServer[] members = entry.members();
        for (int i = 0; i < members.length; i++) {
            if (members[i] != candidates.get(i)) {
                return false;
            }
        }
        return true;
    }
}
