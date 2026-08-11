package com.example.loadbalancer.routing;

import com.example.loadbalancer.backend.BackendRegistry;
import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.backend.BackendSnapshot;
import com.example.loadbalancer.exception.NoHealthyBackendException;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import com.example.loadbalancer.resilience.CircuitBreakerRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns a request into a backend choice.
 *
 * <h2>Why eligibility lives here and not in the strategies</h2>
 * There are five independent reasons a backend may be ineligible: it is not UP, it is not
 * in the matched route's pool, its circuit breaker is open, it already failed this request,
 * or it is draining. If each of the seven strategies had to apply those filters, the rules
 * would drift — and a strategy that forgot the circuit-breaker check would quietly send
 * traffic to a backend the rest of the system had given up on. Filtering once, here, means
 * a strategy's only job is "pick one of these", which is also what makes each of them a
 * dozen testable lines.
 *
 * <h2>Ordering guarantee</h2>
 * The candidate list preserves registry order. That stability is what lets weighted round
 * robin and the consistent-hash ring cache their derived structures against the pool
 * version: an unstable order would look like a new pool on every request and rebuild the
 * ring each time.
 */
@Service
public class BackendSelectionService {

    private final BackendRegistry backendRegistry;
    private final RouteRegistry routeRegistry;
    private final AlgorithmManager algorithmManager;
    private final CircuitBreakerRegistry circuitBreakers;

    public BackendSelectionService(BackendRegistry backendRegistry,
                                   RouteRegistry routeRegistry,
                                   AlgorithmManager algorithmManager,
                                   CircuitBreakerRegistry circuitBreakers) {
        this.backendRegistry = backendRegistry;
        this.routeRegistry = routeRegistry;
        this.algorithmManager = algorithmManager;
        this.circuitBreakers = circuitBreakers;
    }

    /**
     * The result of routing one attempt.
     *
     * @param backend    chosen backend
     * @param algorithm  algorithm that chose it, for logging and metric tags
     * @param routeId    matched route id, or {@code default} for the global pool. Used as a
     *                   metric tag — it is a bounded configured value, unlike a raw URL
     * @param candidates how many backends were eligible, useful when diagnosing a 503
     */
    public record Selection(BackendServer backend, LoadBalancingAlgorithm algorithm, String routeId, int candidates) {
    }

    /** Route id reported when no rule matched and the global pool was used. */
    public static final String DEFAULT_ROUTE = "default";

    /**
     * Selects a backend for one attempt.
     *
     * @param context request metadata, including any backends already tried
     * @return the selection
     * @throws NoHealthyBackendException if nothing is eligible
     */
    public Selection select(LoadBalancingContext context) {
        Optional<RouteRule> route = routeRegistry.match(context.path(), context.method());
        String routeId = route.map(RouteRule::id).orElse(DEFAULT_ROUTE);

        BackendSnapshot snapshot = backendRegistry.snapshot();
        List<BackendServer> candidates = eligible(snapshot, route.orElse(null), context);

        if (candidates.isEmpty()) {
            throw NoHealthyBackendException.forRoute(
                    route.map(RouteRule::pathSpec).orElse(null), snapshot.size());
        }

        LoadBalancingAlgorithm algorithm = route.map(RouteRule::algorithm).filter(java.util.Objects::nonNull)
                .orElseGet(algorithmManager::current);
        LoadBalancingStrategy strategy = algorithmManager.strategyFor(algorithm);

        BackendServer chosen = strategy.selectBackend(candidates, context.withPoolVersion(snapshot.version()));
        if (chosen == null) {
            // Defensive: a strategy returning null would otherwise surface as an NPE deep in
            // the proxy. Report it as the operational condition it actually is.
            throw new NoHealthyBackendException(
                    "Strategy " + algorithm + " returned no backend from " + candidates.size() + " candidate(s)");
        }
        return new Selection(chosen, algorithm, routeId, candidates.size());
    }

    /**
     * Applies every eligibility rule.
     *
     * <p>Note what is deliberately absent: there is no fallback to the global pool when a
     * matched route's backends are all unavailable. See {@link RouteRegistry} for why
     * failing is the correct behaviour there.
     */
    private List<BackendServer> eligible(BackendSnapshot snapshot, RouteRule route, LoadBalancingContext context) {
        List<BackendServer> source = snapshot.backends();
        List<BackendServer> result = new ArrayList<>(Math.min(source.size(), 16));

        for (BackendServer backend : source) {
            if (!backend.isRoutable()) {
                continue;
            }
            if (route != null && route.hasBackendPool() && !route.backendIds().contains(backend.id())) {
                continue;
            }
            if (context.excludedBackendIds().contains(backend.id())) {
                continue;
            }
            if (!circuitBreakers.isAvailable(backend.id())) {
                continue;
            }
            result.add(backend);
        }
        return result;
    }

    /**
     * Answers "is there another backend to fail over to?" without performing a selection.
     *
     * <p>Asked <em>before</em> an attempt is dispatched, so the forwarder knows whether it may
     * discard a retryable 503 in favour of another backend or must relay it to the client.
     * Getting this wrong in the optimistic direction turns a backend's honest 503 into a
     * synthesised one from the ALB, losing the response body the backend meant to send.
     *
     * @param context   the routing context, including backends already tried
     * @param excludeId the backend about to be attempted, which does not count as an alternative
     * @return true if at least one other eligible backend exists
     */
    public boolean hasAlternative(LoadBalancingContext context, String excludeId) {
        Optional<RouteRule> route = routeRegistry.match(context.path(), context.method());
        for (BackendServer backend : eligible(backendRegistry.snapshot(), route.orElse(null), context)) {
            if (!backend.id().equals(excludeId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the number of backends currently eligible for the global pool, ignoring
     *         per-request exclusions. Used by {@code /admin/status} and by readiness.
     */
    public int healthyCount() {
        int count = 0;
        for (BackendServer backend : backendRegistry.all()) {
            if (backend.isRoutable()) {
                count++;
            }
        }
        return count;
    }
}
