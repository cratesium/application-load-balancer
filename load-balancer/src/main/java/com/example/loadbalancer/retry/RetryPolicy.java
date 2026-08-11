package com.example.loadbalancer.retry;

import com.example.loadbalancer.config.LoadBalancerProperties;
import com.example.loadbalancer.proxy.FailureClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides whether a failed attempt may be retried against a different backend.
 *
 * <h2>Retries are dangerous by default, so the defaults are restrictive</h2>
 * A retry is a promise that re-sending the request cannot cause harm. That promise only
 * holds under conditions this class checks explicitly:
 *
 * <ul>
 *   <li><b>The method must be idempotent.</b> A {@code POST /api/orders} that times out may
 *       well have been processed — the backend could have committed the order and died
 *       before the response reached us. Retrying it charges the customer twice. So GET, HEAD
 *       and OPTIONS are retried by default and POST/PATCH are not, unless an operator
 *       explicitly opts in because they know their handlers are idempotent (typically via an
 *       idempotency key).</li>
 *   <li><b>The failure must be safe.</b> Connection refused means the request was never
 *       delivered — unambiguously safe. A response timeout is <em>not</em> unambiguous: the
 *       backend may still be working on it. It is retried anyway (only for idempotent
 *       methods, where a duplicate is harmless) because a hung backend is the most common
 *       thing a retry actually rescues.</li>
 *   <li><b>The status must indicate the backend never did the work.</b> 502, 503 and 504 mean
 *       an upstream problem. 4xx responses are the backend's considered answer — retrying a
 *       404 on another server is pointless, and retrying a 429 makes the rate limit worse.
 *       500 is excluded from the default set too: it usually means the request reached
 *       application code, which may have had side effects before it failed.</li>
 *   <li><b>The response must not be committed.</b> Once bytes are on the wire to the client,
 *       there is no way to retract them; enforced by the proxy service, not here.</li>
 * </ul>
 *
 * <h2>Retry amplification</h2>
 * {@code max-attempts: 2} means at most one extra attempt. Retries multiply load exactly when
 * a system is already failing — with 3 attempts per request, a partial outage triples the
 * traffic hitting the remaining healthy backends and can complete the outage. The circuit
 * breaker is the backstop: a backend that keeps failing stops being a retry target at all.
 */
@Component
public class RetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);

    private final boolean enabled;
    private final int maxAttempts;
    private final Set<String> retryableMethods;
    private final Set<Integer> retryableStatuses;
    private final Duration backoff;
    private final FailureClassifier failureClassifier;

    public RetryPolicy(LoadBalancerProperties properties, FailureClassifier failureClassifier) {
        LoadBalancerProperties.Retry config = properties.retry();
        this.enabled = config.enabled();
        this.maxAttempts = config.maxAttempts();
        this.retryableMethods = config.methods().stream()
                .map(method -> method.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.retryableStatuses = Set.copyOf(config.retryableStatuses());
        this.backoff = config.backoff();
        this.failureClassifier = failureClassifier;

        if (enabled) {
            log.info("Retries enabled: maxAttempts={} methods={} statuses={} backoff={}",
                    maxAttempts, retryableMethods, retryableStatuses, backoff);
            if (retryableMethods.contains("POST") || retryableMethods.contains("PATCH")) {
                log.warn("Retry is enabled for a non-idempotent method ({}). A retried request "
                                + "may be executed more than once by the backends. Ensure the "
                                + "affected endpoints are idempotent.",
                        retryableMethods.stream().filter(m -> m.equals("POST") || m.equals("PATCH")).toList());
            }
        } else {
            log.info("Retries are disabled");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** @return the total number of attempts allowed, including the first. */
    public int maxAttempts() {
        return enabled ? maxAttempts : 1;
    }

    public Duration backoff() {
        return backoff;
    }

    /** @return true if this method is safe to send more than once. */
    public boolean isMethodRetryable(String method) {
        return enabled && method != null && retryableMethods.contains(method.toUpperCase(Locale.ROOT));
    }

    /** @return true if this response status indicates the backend did not do the work. */
    public boolean isStatusRetryable(int status) {
        return enabled && retryableStatuses.contains(status);
    }

    /** @return true if this transport failure is safe to retry elsewhere. */
    public boolean isFailureRetryable(Throwable error) {
        return enabled && failureClassifier.isRetryable(error);
    }

    /**
     * Combines every precondition except response-commitment and candidate availability,
     * which only the proxy service can know.
     *
     * @param method          request method
     * @param attempt         the attempt that just failed, 1-based
     * @param bodyIsReplayable whether the request body can be sent again
     */
    public boolean canRetry(String method, int attempt, boolean bodyIsReplayable) {
        return enabled
                && attempt < maxAttempts
                && isMethodRetryable(method)
                && bodyIsReplayable;
    }
}
