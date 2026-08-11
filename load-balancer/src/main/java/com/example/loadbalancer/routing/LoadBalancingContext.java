package com.example.loadbalancer.routing;

import java.util.Set;

/**
 * Everything a strategy is allowed to know about the request it is routing.
 *
 * <p>Deliberately free of Spring HTTP types. Strategies stay pure functions of
 * {@code (candidates, context)}, which is what makes them trivially unit-testable and
 * what stops routing logic from creeping into the proxy layer.
 *
 * @param requestId          correlation id, also present in logs, metrics and the
 *                           backend request
 * @param method             HTTP method, uppercase
 * @param path               request path without the query string
 * @param clientIp           resolved client IP (see {@code ClientIpResolver}); may be null
 *                           if the peer address was unavailable
 * @param affinityKey        the value hash-based strategies key on. Defaults to the client
 *                           IP; this is the single extension point for cookie-based sticky
 *                           sessions, header-based or tenant-based affinity — a future
 *                           resolver sets a different key and IP_HASH / CONSISTENT_HASH
 *                           need no changes at all
 * @param attempt            1 for the first try, 2 for the first retry, and so on
 * @param excludedBackendIds backends already tried and failed for this request; the
 *                           selector removes these before calling the strategy so a retry
 *                           never lands on the server that just failed
 * @param poolVersion        registry version the candidate list was derived from. Passed
 *                           through so strategies that precompute a structure (weighted
 *                           schedule, hash ring) can tell a stale cache entry from a fresh
 *                           one without reaching back into the registry
 */
public record LoadBalancingContext(
        String requestId,
        String method,
        String path,
        String clientIp,
        String affinityKey,
        int attempt,
        Set<String> excludedBackendIds,
        long poolVersion) {

    public LoadBalancingContext {
        excludedBackendIds = excludedBackendIds == null ? Set.of() : Set.copyOf(excludedBackendIds);
    }

    /**
     * Builds a context for the first attempt, using the client IP as the affinity key.
     */
    public static LoadBalancingContext of(String requestId, String method, String path, String clientIp) {
        return new LoadBalancingContext(requestId, method, path, clientIp, clientIp, 1, Set.of(), 0L);
    }

    /** @return a copy describing the next attempt, with {@code failedBackendIds} excluded. */
    public LoadBalancingContext nextAttempt(Set<String> failedBackendIds) {
        return new LoadBalancingContext(
                requestId, method, path, clientIp, affinityKey, attempt + 1, failedBackendIds, poolVersion);
    }

    /** @return a copy pinned to a specific registry version. */
    public LoadBalancingContext withPoolVersion(long version) {
        return new LoadBalancingContext(
                requestId, method, path, clientIp, affinityKey, attempt, excludedBackendIds, version);
    }

    /**
     * @return the key hash-based strategies should use, never null. Falls back to the
     *         request id when there is no client IP, which degrades affinity to "random
     *         but stable for this request" instead of throwing or sending every anonymous
     *         client to one backend.
     */
    public String resolvedAffinityKey() {
        if (affinityKey != null && !affinityKey.isBlank()) {
            return affinityKey;
        }
        if (clientIp != null && !clientIp.isBlank()) {
            return clientIp;
        }
        return requestId == null ? "" : requestId;
    }
}
