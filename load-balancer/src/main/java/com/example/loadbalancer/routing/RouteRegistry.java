package com.example.loadbalancer.routing;

import com.example.loadbalancer.config.LoadBalancerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the ordered list of {@link RouteRule}s and answers "which rule governs this request".
 *
 * <h2>Ordering</h2>
 * Rules are evaluated in declaration order and the first match wins. Declaration order
 * rather than "most specific wins" is a deliberate choice: specificity heuristics are
 * surprising when two patterns overlap partially, whereas a top-to-bottom list is how every
 * operator already reads nginx and HAProxy configs. Method-qualified rules must therefore be
 * declared before the catch-all for the same path, which is exactly how the requirements'
 * GET-vs-POST example reads.
 *
 * <h2>Why no fallback to the global pool on a match</h2>
 * If a rule matches but every backend in its pool is unavailable, the request fails with
 * 503 — it is <em>not</em> retried against the global pool. Routes exist to express "these
 * URLs are served by this service"; quietly sending {@code /api/orders} to the users
 * service because the orders service is down would turn an outage into data corruption.
 *
 * <p>Published through an {@link AtomicReference} so {@code /admin/config/reload} can swap
 * the whole rule set without locking the request path.
 */
@Component
public class RouteRegistry {

    private static final Logger log = LoggerFactory.getLogger(RouteRegistry.class);

    private final PathPatternParser parser = new PathPatternParser();
    private final AtomicReference<List<RouteRule>> rules = new AtomicReference<>(List.of());

    public RouteRegistry(LoadBalancerProperties properties) {
        replaceAll(properties.routes());
        if (rules.get().isEmpty()) {
            log.info("No routes configured; all traffic uses the global backend pool");
        } else {
            log.info("Configured {} route(s): {}", rules.get().size(),
                    rules.get().stream().map(RouteRule::pathSpec).toList());
        }
    }

    /**
     * Finds the first rule matching the request.
     *
     * @param path   request path without query string
     * @param method uppercase HTTP method
     * @return the governing rule, or empty to use the global pool and global algorithm
     */
    public Optional<RouteRule> match(String path, String method) {
        List<RouteRule> current = rules.get();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        PathContainer container = PathContainer.parsePath(path);
        for (RouteRule rule : current) {
            if (rule.matches(container, method)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    public List<RouteRule> all() {
        return rules.get();
    }

    /**
     * Compiles and atomically installs a new rule set.
     *
     * <p>Compilation happens before the swap, so a syntactically invalid pattern in a
     * reload fails the reload and leaves the previous routes serving traffic.
     */
    public void replaceAll(List<LoadBalancerProperties.Route> routes) {
        List<RouteRule> compiled = new ArrayList<>();
        int index = 0;
        for (LoadBalancerProperties.Route route : routes == null ? List.<LoadBalancerProperties.Route>of() : routes) {
            index++;
            String id = (route.id() == null || route.id().isBlank()) ? "route-" + index : route.id();
            PathPattern pattern;
            try {
                pattern = parser.parse(route.path());
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException(
                        "Route '" + id + "' has an invalid path pattern '" + route.path() + "': " + ex.getMessage(), ex);
            }
            compiled.add(new RouteRule(id, route.path(), pattern, route.methods(),
                    route.backends(), route.algorithm()));
        }
        rules.set(List.copyOf(compiled));
    }
}
