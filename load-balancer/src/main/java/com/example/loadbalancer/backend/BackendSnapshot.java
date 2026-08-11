package com.example.loadbalancer.backend;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable view of pool <em>membership</em> at a point in time.
 *
 * <p>Published atomically by {@link BackendRegistry}. A request thread reads the current
 * snapshot once and routes against that view for its whole lifetime, which means a
 * concurrent {@code POST /admin/backends} can never be observed half-applied and never
 * makes a request thread block.
 *
 * @param backends membership in configuration order; never modified after construction
 * @param byId     index for O(1) lookups by backend id
 * @param version  monotonically increasing; strategies use it to invalidate caches such
 *                 as the consistent-hash ring and the weighted round-robin schedule
 */
public record BackendSnapshot(List<BackendServer> backends, Map<String, BackendServer> byId, long version) {

    public static BackendSnapshot of(List<BackendServer> backends, long version) {
        Map<String, BackendServer> index = new LinkedHashMap<>(Math.max(4, backends.size() * 2));
        for (BackendServer backend : backends) {
            index.put(backend.id(), backend);
        }
        return new BackendSnapshot(List.copyOf(backends), Collections.unmodifiableMap(index), version);
    }

    public BackendServer find(String id) {
        return byId.get(id);
    }

    public int size() {
        return backends.size();
    }
}
