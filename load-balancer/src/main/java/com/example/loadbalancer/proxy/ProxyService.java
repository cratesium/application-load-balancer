package com.example.loadbalancer.proxy;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.config.LoadBalancerProperties;
import com.example.loadbalancer.exception.BackendUnavailableException;
import com.example.loadbalancer.exception.LoadBalancerException;
import com.example.loadbalancer.health.PassiveHealthMonitor;
import com.example.loadbalancer.metrics.BackendMetrics;
import com.example.loadbalancer.metrics.LoadBalancerMetrics;
import com.example.loadbalancer.observability.AccessLogger;
import com.example.loadbalancer.resilience.CircuitBreakerRegistry;
import com.example.loadbalancer.retry.RetryPolicy;
import com.example.loadbalancer.routing.BackendSelectionService;
import com.example.loadbalancer.routing.LoadBalancingContext;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Orchestrates the full request lifecycle. The flow implemented here is:
 *
 * <pre>
 *   request id  →  framing validation  →  capture body  →  attempt(1)
 *        ↑                                                     │
 *        │                                    ┌────────────────┴─────────────────┐
 *        │                                    ▼                                  ▼
 *        │                            select backend                     no candidate → 503
 *        │                                    │
 *        │                        circuit breaker permit
 *        │                                    │
 *        │                          active++  (usingWhen)
 *        │                                    │
 *        │                            forward + relay
 *        │                                    │
 *        │                     ┌──────────────┴──────────────┐
 *        │                     ▼                             ▼
 *        │                  success                       failure
 *        │            record success on CB,      retryable and attempts left?
 *        │            passive health, metrics       yes → attempt(n+1) excluding
 *        │                                                 this backend
 *        │                                          no  → mapped error response
 *        └───────────── active--, access log, metrics (always) ──────────────────┘
 * </pre>
 *
 * <h2>Connection counting cannot leak</h2>
 * The active-connection counter is managed with {@link Mono#usingWhen}, the reactive
 * equivalent of try-with-resources. It takes explicit handlers for <em>all three</em>
 * terminal signals — complete, error and cancel — so the counter is decremented even when the
 * client disconnects mid-response, which a naive {@code doOnSuccess}/{@code doOnError} pair
 * would miss entirely. That matters more than it sounds: a leaked increment is permanent, and
 * under least-connections routing a backend with a phantom count of 50 stops receiving
 * traffic forever, silently reducing pool capacity with no error anywhere.
 *
 * <h2>Retries select a new backend, not the same one</h2>
 * Each attempt runs a fresh selection with the previously-tried backends excluded, so a retry
 * genuinely fails over rather than re-hitting the broken server. This is why the retry is a
 * recursive call around selection rather than a {@code retryWhen} operator, which would simply
 * resubscribe the same already-bound backend.
 */
@Service
public class ProxyService {

    private final BackendSelectionService selectionService;
    private final RequestForwarder forwarder;
    private final RetryPolicy retryPolicy;
    private final CircuitBreakerRegistry circuitBreakers;
    private final PassiveHealthMonitor passiveHealth;
    private final ClientIpResolver clientIpResolver;
    private final ProxyHeaders proxyHeaders;
    private final FailureClassifier failureClassifier;
    private final LoadBalancerMetrics metrics;
    private final BackendMetrics backendMetrics;
    private final AccessLogger accessLogger;

    private final Duration requestTimeout;
    private final long maxRequestBodyBytes;
    private final long maxBufferedBodyBytes;
    private final boolean bufferRequestBody;
    private final String requestIdHeader;

    public ProxyService(BackendSelectionService selectionService,
                        RequestForwarder forwarder,
                        RetryPolicy retryPolicy,
                        CircuitBreakerRegistry circuitBreakers,
                        PassiveHealthMonitor passiveHealth,
                        ClientIpResolver clientIpResolver,
                        ProxyHeaders proxyHeaders,
                        FailureClassifier failureClassifier,
                        LoadBalancerMetrics metrics,
                        BackendMetrics backendMetrics,
                        AccessLogger accessLogger,
                        LoadBalancerProperties properties) {
        this.selectionService = selectionService;
        this.forwarder = forwarder;
        this.retryPolicy = retryPolicy;
        this.circuitBreakers = circuitBreakers;
        this.passiveHealth = passiveHealth;
        this.clientIpResolver = clientIpResolver;
        this.proxyHeaders = proxyHeaders;
        this.failureClassifier = failureClassifier;
        this.metrics = metrics;
        this.backendMetrics = backendMetrics;
        this.accessLogger = accessLogger;
        this.requestTimeout = properties.timeouts().request();
        this.maxRequestBodyBytes = properties.limits().maxRequestBody().toBytes();
        this.maxBufferedBodyBytes = properties.retry().maxBufferedBody().toBytes();
        this.bufferRequestBody = properties.retry().bufferRequestBody();
        this.requestIdHeader = properties.proxy().requestIdHeader();
    }

    /**
     * Proxies one client request end to end.
     *
     * @return a Mono completing when the client response is fully written. Errors are left
     *         for {@code ProxyExceptionHandler} to render, so error mapping lives in one place
     */
    public Mono<Void> proxy(ServerWebExchange exchange) {
        return Mono.defer(() -> {
            ServerHttpRequest request = exchange.getRequest();
            proxyHeaders.validateRequestFraming(request);

            ProxyRequestContext context = createContext(exchange);
            // Echo the id to the client immediately so it is present even on error paths.
            exchange.getResponse().getHeaders().set(requestIdHeader, context.requestId());
            metrics.incrementActive();

            boolean wantReplayableBody = bufferRequestBody
                    && retryPolicy.isMethodRetryable(context.method());

            return ProxyRequestBody
                    .capture(request, wantReplayableBody, maxBufferedBodyBytes, maxRequestBodyBytes)
                    .doOnNext(context::body)
                    .flatMap(body -> attempt(context))
                    // End-to-end budget covering selection, all attempts and the response
                    // stream. Distinct from the per-attempt response timeout: a request that
                    // retries twice, each just under the response timeout, must still be
                    // bounded overall.
                    .timeout(requestTimeout)
                    .doOnSuccess(ignored -> onRequestComplete(context))
                    .doOnError(error -> onRequestFailed(context, error))
                    // Every failure leaving the proxy is translated into the ALB's own
                    // exception hierarchy, which carries the right status and error code.
                    // Without this, transport-level failures — Netty's ReadTimeoutException for
                    // a response timeout, a raw ConnectException for a refused connection —
                    // reach the error handler unrecognised and are reported as 500, blaming the
                    // load balancer for an upstream problem. Every timeout must be a 504 and
                    // every unreachable backend a 502, whatever library type expressed it.
                    .onErrorMap(error -> failureClassifier.toException(error, backendIdOf(context)))
                    .doFinally(signal -> metrics.decrementActive());
        });
    }

    /** Runs one attempt, recursing into the next on a retryable failure. */
    private Mono<Void> attempt(ProxyRequestContext context) {
        return Mono.defer(() -> {
            context.beginAttempt();
            LoadBalancingContext routingContext = context.toLoadBalancingContext();
            BackendSelectionService.Selection selection = selectionService.select(routingContext);

            BackendServer backend = selection.backend();
            context.currentBackend(backend);
            context.routeId(selection.routeId());
            context.algorithm(selection.algorithm().name());

            if (!circuitBreakers.tryAcquire(backend.id())) {
                // Raced with the breaker opening between filtering and dispatch. Treat it as
                // a normal attempt failure so the retry path can pick another backend.
                return Mono.error(new BackendUnavailableException(backend.id(),
                        FailureClassifier.CIRCUIT_OPEN,
                        "Circuit breaker is open for the selected backend", null));
            }

            boolean retryAllowed = canRetry(context, routingContext);
            long attemptStart = System.nanoTime();

            return Mono.usingWhen(
                            Mono.fromCallable(() -> {
                                backend.acquireConnection();
                                return backend;
                            }),
                            acquired -> forwarder.forward(acquired, context, retryAllowed),
                            acquired -> Mono.fromRunnable(acquired::releaseConnection),
                            (acquired, error) -> Mono.fromRunnable(acquired::releaseConnection),
                            acquired -> Mono.fromRunnable(acquired::releaseConnection))
                    .doOnSuccess(ignored -> onAttemptSuccess(context, backend, attemptStart))
                    .doOnError(error -> onAttemptFailure(context, backend, error, attemptStart));
        }).onErrorResume(error -> retryOrFail(context, error));
    }

    /**
     * Decides whether another attempt is possible <em>before</em> dispatching this one.
     *
     * <p>Checking candidate availability up front is what lets the forwarder relay a 503 from
     * the last reachable backend instead of discarding it in the hope of a retry that could
     * never happen.
     */
    private boolean canRetry(ProxyRequestContext context, LoadBalancingContext routingContext) {
        if (!retryPolicy.canRetry(context.method(), context.attempts(), context.body().isReplayable())) {
            return false;
        }
        // An alternative must exist once the backend we are about to try is excluded.
        return selectionService.hasAlternative(routingContext, context.currentBackend().id());
    }

    private Mono<Void> retryOrFail(ProxyRequestContext context, Throwable error) {
        boolean responseCommitted = context.exchange().getResponse().isCommitted();

        if (error instanceof RetryableResponseException retryable && !responseCommitted) {
            context.markTried(retryable.backendId());
            metrics.recordRetry("status_" + retryable.status());
            accessLogger.logRetry(context, retryable.backendId(), "status=" + retryable.status());
            return attempt(context).delaySubscription(retryPolicy.backoff());
        }

        BackendServer backend = context.currentBackend();
        boolean canRetry = !responseCommitted
                && backend != null
                && retryPolicy.canRetry(context.method(), context.attempts(), context.body().isReplayable())
                && retryPolicy.isFailureRetryable(error)
                && selectionService.hasAlternative(context.toLoadBalancingContext(), backend.id());

        if (canRetry) {
            String kind = failureClassifier.classify(error);
            context.markTried(backend.id());
            metrics.recordRetry(kind);
            accessLogger.logRetry(context, backend.id(), kind);
            return attempt(context).delaySubscription(retryPolicy.backoff());
        }
        return Mono.error(error);
    }

    // ------------------------------------------------------------------
    // Outcome accounting
    // ------------------------------------------------------------------

    private void onAttemptSuccess(ProxyRequestContext context, BackendServer backend, long attemptStart) {
        long duration = System.nanoTime() - attemptStart;
        int status = context.backendStatus();
        backendMetrics.recordBackendRequest(backend.id(), context.method(), status, duration);

        // A 5xx is a backend failure for health and circuit-breaking purposes even though it
        // is a valid HTTP response that we relayed. 4xx is not: that is the backend working
        // correctly and rejecting the client.
        if (status >= 500) {
            backend.recordFailure("HTTP_" + status);
            circuitBreakers.recordFailure(backend.id());
            passiveHealth.recordFailure(backend, "HTTP_" + status);
        } else {
            backend.recordSuccess();
            circuitBreakers.recordSuccess(backend.id());
            passiveHealth.recordSuccess(backend);
        }
    }

    private void onAttemptFailure(ProxyRequestContext context, BackendServer backend,
                                  Throwable error, long attemptStart) {
        if (error instanceof RetryableResponseException retryable) {
            // Counted as a backend failure, but the response itself was well-formed.
            backend.recordFailure("HTTP_" + retryable.status());
            circuitBreakers.recordFailure(backend.id());
            passiveHealth.recordFailure(backend, "HTTP_" + retryable.status());
            backendMetrics.recordBackendRequest(backend.id(), context.method(),
                    retryable.status(), System.nanoTime() - attemptStart);
            return;
        }
        String kind = failureClassifier.classify(error);
        backend.recordFailure(kind);
        circuitBreakers.recordFailure(backend.id());
        passiveHealth.recordFailure(backend, kind);
        backendMetrics.recordBackendFailure(backend.id(), kind, System.nanoTime() - attemptStart);
    }

    private void onRequestComplete(ProxyRequestContext context) {
        long duration = context.elapsedNanos();
        int status = statusOf(context);
        metrics.recordRequest(context.algorithm(), context.method(), status, context.routeId(), duration);
        accessLogger.logSuccess(context, status, duration);
    }

    private void onRequestFailed(ProxyRequestContext context, Throwable error) {
        long duration = context.elapsedNanos();
        LoadBalancerException mapped = failureClassifier.toException(error, backendIdOf(context));
        int status = mapped.status().value();
        metrics.recordRequest(context.algorithm(), context.method(), status, context.routeId(), duration);
        metrics.recordFailure(mapped.errorCode(), context.routeId());
        accessLogger.logFailure(context, status, mapped.errorCode(), failureClassifier.classify(error), duration);
    }

    private int statusOf(ProxyRequestContext context) {
        var statusCode = context.exchange().getResponse().getStatusCode();
        return statusCode != null ? statusCode.value() : context.backendStatus();
    }

    private String backendIdOf(ProxyRequestContext context) {
        BackendServer backend = context.currentBackend();
        return backend == null ? "none" : backend.id();
    }

    private ProxyRequestContext createContext(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String clientIp = clientIpResolver.resolve(request);
        String peer = clientIpResolver.peerAddress(request);
        boolean trustedPeer = peer != null && clientIpResolver.isTrusted(peer);

        return new ProxyRequestContext(
                exchange,
                resolveRequestId(request),
                request.getMethod().name(),
                request.getURI().getRawPath(),
                request.getURI().getRawQuery(),
                clientIp,
                peer,
                trustedPeer,
                System.nanoTime());
    }

    /**
     * Reuses an inbound request id when present so a trace spanning several services shares
     * one id, and generates one otherwise. The value is length-checked and sanitised: it is
     * echoed into a response header and into logs, so an unbounded or control-character-laden
     * value from a client would be a header-injection and log-forging vector.
     */
    private String resolveRequestId(ServerHttpRequest request) {
        String incoming = request.getHeaders().getFirst(requestIdHeader);
        if (incoming != null && !incoming.isBlank() && incoming.length() <= 128 && isSafeId(incoming)) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    private static boolean isSafeId(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
