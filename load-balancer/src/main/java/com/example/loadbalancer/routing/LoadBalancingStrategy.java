package com.example.loadbalancer.routing;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;

import java.util.List;

/**
 * Chooses one backend from a set of candidates.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code healthyBackends} is non-empty, already filtered to routable backends,
 *       already restricted to the route's pool, already stripped of backends excluded by a
 *       retry, and already stripped of backends whose circuit breaker is open. A strategy
 *       must not re-check any of that — the filtering lives in
 *       {@link BackendSelectionService} so that all seven strategies agree on eligibility.</li>
 *   <li>The returned backend must be one of the supplied candidates.</li>
 *   <li>Implementations must be thread-safe and must not block. They are singletons called
 *       from Netty event-loop threads; a lock held here stalls every connection on that
 *       loop.</li>
 *   <li>Implementations must not mutate the registry, the backends or the candidate list.
 *       Selection is a read-only decision; accounting happens in the proxy layer.</li>
 * </ul>
 */
public interface LoadBalancingStrategy {

    /**
     * Selects a backend.
     *
     * @param healthyBackends non-empty list of eligible candidates
     * @param context         request metadata; hash-based strategies read the affinity key
     * @return the chosen backend, never null
     */
    BackendServer selectBackend(List<BackendServer> healthyBackends, LoadBalancingContext context);

    /** @return the algorithm this strategy implements; used to register it in the factory. */
    LoadBalancingAlgorithm algorithm();
}
