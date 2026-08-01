package com.example.loadbalancer.config;

import com.example.loadbalancer.resilience.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Cross-field configuration validation, run before the application starts serving.
 *
 * <h2>Why this exists alongside Bean Validation</h2>
 * Annotations catch single-field problems ({@code port} out of range, {@code weight} not
 * positive). They cannot catch the ones that actually cause incidents, which are all
 * relationships: two backends sharing an id, a route pointing at a backend that does not exist,
 * a response timeout longer than the request timeout that makes retries impossible, a
 * circuit-breaker window larger than the implementation supports.
 *
 * <p>Everything here fails the boot rather than warning. A load balancer that starts with a
 * broken route silently blackholes a subset of traffic — far worse than not starting at all,
 * because a process that refuses to start is caught by the deployment, while one that starts
 * misconfigured is caught by users. Genuinely ambiguous settings are warnings instead, and each
 * warning says what the consequence is.
 */
@Component
public class ConfigurationValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationValidator.class);

    private final LoadBalancerProperties properties;

    public ConfigurationValidator(LoadBalancerProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        validate(properties);
        log.info("Configuration validated: listen={}:{} algorithm={} backends={} routes={}",
                properties.listen().host(), properties.listen().port(), properties.algorithm(),
                properties.backends().size(), properties.routes().size());
    }

    /**
     * Validates a configuration tree.
     *
     * <p>Static so that {@link ConfigurationReloader} can validate a candidate configuration
     * before applying it, using exactly the same rules as startup.
     *
     * @throws IllegalStateException listing every problem found, not just the first
     */
    public static void validate(LoadBalancerProperties properties) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        validateBackends(properties, errors);
        validateRoutes(properties, errors);
        validateTimeouts(properties, errors, warnings);
        validateCircuitBreaker(properties, errors);
        validateRetry(properties, errors, warnings);
        validateLimits(properties, errors, warnings);
        validateAdmin(properties, warnings);
        validateHealthCheck(properties, warnings);

        warnings.forEach(warning -> log.warn("Configuration warning: {}", warning));

        if (!errors.isEmpty()) {
            StringBuilder message = new StringBuilder("Invalid load balancer configuration:");
            for (int i = 0; i < errors.size(); i++) {
                message.append("\n  ").append(i + 1).append(". ").append(errors.get(i));
            }
            throw new IllegalStateException(message.toString());
        }
    }

    private static void validateBackends(LoadBalancerProperties properties, List<String> errors) {
        if (properties.backends().isEmpty()) {
            errors.add("load-balancer.backends must contain at least one backend");
            return;
        }
        Set<String> ids = new HashSet<>();
        Set<String> addresses = new HashSet<>();
        for (LoadBalancerProperties.Backend backend : properties.backends()) {
            if (!ids.add(backend.id())) {
                errors.add("duplicate backend id '" + backend.id()
                        + "'; ids are identities and must be unique");
            }
            String address = backend.host() + ":" + backend.port();
            if (!addresses.add(address)) {
                // Not an error: two ids on one address is legitimate for weighting a
                // multi-process host, but it is far more often a copy-paste mistake.
                log.warn("Configuration warning: backends '{}' share the address {}; "
                        + "verify this is intentional", ids, address);
            }
            if (properties.listen().port() > 0
                    && backend.port() == properties.listen().port()
                    && isLoopback(backend.host())) {
                errors.add("backend '" + backend.id() + "' points at the load balancer's own "
                        + "listen address (" + address + "), which would cause an infinite proxy loop");
            }
        }
    }

    private static void validateRoutes(LoadBalancerProperties properties, List<String> errors) {
        Set<String> backendIds = new HashSet<>();
        properties.backends().forEach(backend -> backendIds.add(backend.id()));

        Set<String> routeIds = new HashSet<>();
        int index = 0;
        for (LoadBalancerProperties.Route route : properties.routes()) {
            index++;
            String routeName = route.id() == null || route.id().isBlank() ? "route-" + index : route.id();
            if (!routeIds.add(routeName)) {
                errors.add("duplicate route id '" + routeName + "'");
            }
            if (!route.path().startsWith("/")) {
                errors.add("route '" + routeName + "' path must start with '/' but was '" + route.path() + "'");
            }
            for (String backendId : route.backends()) {
                if (!backendIds.contains(backendId)) {
                    // Hard failure: a route whose pool does not exist blackholes every request
                    // that matches it, and it matches silently.
                    errors.add("route '" + routeName + "' references unknown backend '" + backendId + "'");
                }
            }
            for (String method : route.methods()) {
                if (!isKnownMethod(method)) {
                    errors.add("route '" + routeName + "' lists unsupported HTTP method '" + method + "'");
                }
            }
        }
    }

    private static void validateTimeouts(LoadBalancerProperties properties,
                                         List<String> errors, List<String> warnings) {
        LoadBalancerProperties.Timeouts timeouts = properties.timeouts();
        if (timeouts.connection().isZero() || timeouts.connection().isNegative()) {
            errors.add("timeouts.connection must be positive");
        }
        if (timeouts.response().isZero() || timeouts.response().isNegative()) {
            errors.add("timeouts.response must be positive");
        }
        if (timeouts.request().isZero() || timeouts.request().isNegative()) {
            errors.add("timeouts.request must be positive");
        }
        if (timeouts.request().compareTo(timeouts.response()) < 0) {
            errors.add("timeouts.request (" + timeouts.request() + ") must be >= timeouts.response ("
                    + timeouts.response() + "); otherwise the end-to-end budget expires before a "
                    + "single attempt can complete and no request can ever succeed");
        }
        if (properties.retry().enabled() && properties.retry().maxAttempts() > 1) {
            // Each attempt can consume up to the response timeout, so the end-to-end budget
            // must cover all of them or later attempts are cut off mid-flight.
            long worstCase = timeouts.response().toMillis() * properties.retry().maxAttempts();
            if (timeouts.request().toMillis() < worstCase) {
                warnings.add("timeouts.request (" + timeouts.request() + ") is less than "
                        + "timeouts.response x retry.max-attempts (" + worstCase + "ms); the final "
                        + "retry attempt may be cut short by the end-to-end timeout");
            }
        }
        if (timeouts.connection().compareTo(timeouts.response()) > 0) {
            warnings.add("timeouts.connection (" + timeouts.connection() + ") is longer than "
                    + "timeouts.response (" + timeouts.response() + "), which is unusual: "
                    + "connecting should be much faster than responding");
        }
    }

    private static void validateCircuitBreaker(LoadBalancerProperties properties, List<String> errors) {
        LoadBalancerProperties.CircuitBreaker breaker = properties.circuitBreaker();
        if (!breaker.enabled()) {
            return;
        }
        if (breaker.slidingWindowSize() > CircuitBreaker.MAX_WINDOW_SIZE) {
            errors.add("circuit-breaker.sliding-window-size must be <= " + CircuitBreaker.MAX_WINDOW_SIZE
                    + " (the window is a 64-bit bitset) but was " + breaker.slidingWindowSize());
        }
        if (breaker.minimumCalls() > breaker.slidingWindowSize()) {
            errors.add("circuit-breaker.minimum-calls (" + breaker.minimumCalls()
                    + ") must be <= sliding-window-size (" + breaker.slidingWindowSize()
                    + "); otherwise the breaker can never accumulate enough calls to evaluate");
        }
        if (breaker.halfOpenSuccessesToClose() > breaker.halfOpenMaxCalls()) {
            errors.add("circuit-breaker.half-open-successes-to-close (" + breaker.halfOpenSuccessesToClose()
                    + ") must be <= half-open-max-calls (" + breaker.halfOpenMaxCalls()
                    + "); otherwise the breaker can never close");
        }
        if (breaker.openDuration().isZero() || breaker.openDuration().isNegative()) {
            errors.add("circuit-breaker.open-duration must be positive");
        }
    }

    private static void validateRetry(LoadBalancerProperties properties,
                                      List<String> errors, List<String> warnings) {
        LoadBalancerProperties.Retry retry = properties.retry();
        if (!retry.enabled()) {
            return;
        }
        for (String method : retry.methods()) {
            if (!isKnownMethod(method)) {
                errors.add("retry.methods contains unsupported HTTP method '" + method + "'");
            }
        }
        for (Integer status : retry.retryableStatuses()) {
            if (status < 100 || status > 599) {
                errors.add("retry.retryable-statuses contains invalid status " + status);
            }
            if (status >= 400 && status < 500) {
                warnings.add("retry.retryable-statuses includes " + status + ", a client error. "
                        + "Retrying a 4xx on another backend cannot help: the backend already gave "
                        + "its considered answer.");
            }
        }
        boolean nonIdempotent = retry.methods().stream()
                .map(method -> method.toUpperCase(Locale.ROOT))
                .anyMatch(method -> method.equals("POST") || method.equals("PATCH")
                        || method.equals("DELETE") || method.equals("PUT"));
        if (nonIdempotent && !retry.bufferRequestBody()) {
            warnings.add("retry.methods includes a method that usually carries a body, but "
                    + "retry.buffer-request-body is false. Those requests will not be retried, "
                    + "because a streamed body cannot be replayed.");
        }
        if (nonIdempotent) {
            warnings.add("retry.methods includes a non-idempotent method. A retried request may be "
                    + "executed more than once by the backends; ensure the endpoints are idempotent.");
        }
    }

    private static void validateLimits(LoadBalancerProperties properties,
                                       List<String> errors, List<String> warnings) {
        LoadBalancerProperties.Limits limits = properties.limits();
        if (limits.maxRequestBody().toBytes() <= 0) {
            errors.add("limits.max-request-body must be positive");
        }
        if (limits.maxHeaderSize().toBytes() <= 0) {
            errors.add("limits.max-header-size must be positive");
        }
        if (properties.retry().bufferRequestBody()
                && properties.retry().maxBufferedBody().toBytes() > limits.maxRequestBody().toBytes()) {
            warnings.add("retry.max-buffered-body (" + properties.retry().maxBufferedBody()
                    + ") exceeds limits.max-request-body (" + limits.maxRequestBody()
                    + "); the smaller limit applies");
        }
        long worstCaseHeap = (long) limits.maxConcurrentRequests() * properties.retry().maxBufferedBody().toBytes();
        if (properties.retry().bufferRequestBody() && worstCaseHeap > 1L << 30) {
            warnings.add("limits.max-concurrent-requests x retry.max-buffered-body is "
                    + (worstCaseHeap >> 20) + "MB of worst-case heap for buffered request bodies; "
                    + "lower one of them or ensure the heap is sized for it");
        }
    }

    private static void validateAdmin(LoadBalancerProperties properties, List<String> warnings) {
        LoadBalancerProperties.Admin admin = properties.admin();
        if (admin.enabled() && (admin.token() == null || admin.token().isBlank())) {
            warnings.add("admin.enabled is true but admin.token is not set. Every admin request will "
                    + "be rejected. Set it from an environment variable such as ALB_ADMIN_TOKEN.");
        }
    }

    private static void validateHealthCheck(LoadBalancerProperties properties, List<String> warnings) {
        LoadBalancerProperties.HealthCheck healthCheck = properties.healthCheck();
        if (!healthCheck.enabled()) {
            warnings.add("health-check.enabled is false. Backends will never be promoted back to UP "
                    + "automatically after a failure.");
            return;
        }
        if (healthCheck.responseTimeout().compareTo(healthCheck.interval()) > 0) {
            warnings.add("health-check.response-timeout (" + healthCheck.responseTimeout()
                    + ") exceeds health-check.interval (" + healthCheck.interval()
                    + "); probe rounds will be skipped while a slow probe is outstanding");
        }
        long detectionMillis = healthCheck.interval().toMillis() * healthCheck.failureThreshold();
        if (detectionMillis > 60_000) {
            warnings.add("health-check.interval x failure-threshold means an outage takes up to "
                    + (detectionMillis / 1000) + "s to detect actively. Passive health checking "
                    + "reacts faster; make sure it is enabled.");
        }
    }

    private static boolean isKnownMethod(String method) {
        return switch (method == null ? "" : method.toUpperCase(Locale.ROOT)) {
            case "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE" -> true;
            default -> false;
        };
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "0.0.0.0".equals(host);
    }
}
