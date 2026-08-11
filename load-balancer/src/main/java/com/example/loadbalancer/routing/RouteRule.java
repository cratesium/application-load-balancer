package com.example.loadbalancer.routing;

import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One routing rule: "requests matching this path (and optionally these methods) go to this
 * subset of backends, optionally with a different algorithm".
 *
 * <p>Immutable and pre-compiled — the {@link PathPattern} is parsed once at startup, not per
 * request. Matching is the same {@code PathPattern} engine Spring WebFlux itself uses, so
 * {@code /api/users/**} means exactly what a Spring developer expects it to mean.
 *
 * @param id          stable identifier, used in logs, metrics tags and the admin API
 * @param pathSpec    the pattern as written in configuration, kept for display
 * @param pattern     the compiled pattern
 * @param methods     HTTP methods this rule applies to; empty means all methods
 * @param backendIds  the backends this route may use; empty means the global pool
 * @param algorithm   per-route algorithm override; null means "use the active global one"
 */
public record RouteRule(
        String id,
        String pathSpec,
        PathPattern pattern,
        Set<String> methods,
        List<String> backendIds,
        LoadBalancingAlgorithm algorithm) {

    public RouteRule {
        methods = methods == null ? Set.of()
                : methods.stream().map(m -> m.toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        backendIds = backendIds == null ? List.of() : List.copyOf(backendIds);
    }

    /**
     * @param path   request path, already decoded
     * @param method uppercase HTTP method
     * @return true if this rule governs the request
     */
    public boolean matches(PathContainer path, String method) {
        if (!methods.isEmpty() && !methods.contains(method)) {
            return false;
        }
        return pattern.matches(path);
    }

    /** @return true if this route restricts traffic to a specific set of backends. */
    public boolean hasBackendPool() {
        return !backendIds.isEmpty();
    }
}
