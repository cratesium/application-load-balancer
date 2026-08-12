package com.example.loadbalancer.proxy;

import com.example.loadbalancer.exception.BackendPoolExhaustedException;
import com.example.loadbalancer.exception.BackendTimeoutException;
import com.example.loadbalancer.exception.BackendUnavailableException;
import com.example.loadbalancer.exception.LoadBalancerException;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Turns whatever the HTTP client threw into a specific, actionable classification.
 *
 * <h2>Why bother distinguishing these</h2>
 * Every failure below could be reported as "502 backend error", and that is exactly what
 * makes proxy outages hard to debug at 3am. The distinctions drive real decisions:
 * <ul>
 *   <li><b>Connection refused</b> — the process is not listening. Retry elsewhere
 *       immediately; the backend is genuinely gone.</li>
 *   <li><b>Response timeout</b> — the process accepted the connection and went quiet. It is
 *       alive and probably overloaded, so retrying it aggressively makes things worse.</li>
 *   <li><b>Pool acquire timeout</b> — the <em>load balancer</em> ran out of connections to
 *       that backend. Nothing is wrong with the backend at all; the ALB's own pool is the
 *       bottleneck, and reporting it as a backend fault sends the operator to the wrong
 *       machine.</li>
 *   <li><b>Premature close</b> — usually a keep-alive race, where the backend closed an
 *       idle connection just as we reused it. Safe to retry, and a signal that
 *       {@code max-idle-time} is too high relative to the backend's keep-alive timeout.</li>
 * </ul>
 * The classification becomes a metric tag and a log field, so these are separable on a
 * dashboard rather than a single undifferentiated 502 line.
 */
@Component
public class FailureClassifier {

    /** Failure kinds, used as bounded metric tag values. */
    public static final String CONNECT_TIMEOUT = "CONNECT_TIMEOUT";
    public static final String RESPONSE_TIMEOUT = "RESPONSE_TIMEOUT";
    public static final String REQUEST_TIMEOUT = "REQUEST_TIMEOUT";
    public static final String CONNECTION_REFUSED = "CONNECTION_REFUSED";
    public static final String CONNECTION_RESET = "CONNECTION_RESET";
    public static final String PREMATURE_CLOSE = "PREMATURE_CLOSE";
    public static final String POOL_ACQUIRE_TIMEOUT = "POOL_ACQUIRE_TIMEOUT";
    public static final String DNS_FAILURE = "DNS_FAILURE";
    public static final String TLS_FAILURE = "TLS_FAILURE";
    public static final String CIRCUIT_OPEN = "CIRCUIT_OPEN";
    public static final String BACKEND_ERROR = "BACKEND_ERROR";
    public static final String UNKNOWN = "UNKNOWN";

    /**
     * Classifies a throwable into one of the constants above.
     *
     * <p>Unwraps {@link WebClientRequestException}, which wraps the real transport cause.
     * Pool exhaustion is matched by exception class name rather than by type: the concrete
     * class lives in reactor-pool internals and has moved between versions, and a compile-time
     * dependency on an internal type would be more fragile than a name check.
     */
    public String classify(Throwable error) {
        Throwable cause = unwrap(error);

        if (cause instanceof ConnectTimeoutException) {
            return CONNECT_TIMEOUT;
        }
        if (cause instanceof ReadTimeoutException) {
            return RESPONSE_TIMEOUT;
        }
        if (cause instanceof ConnectException) {
            // "Connection refused" is the common case; a connect timeout surfaces as the
            // Netty type above, so anything landing here is a refusal or unreachable host.
            return CONNECTION_REFUSED;
        }
        if (cause instanceof UnknownHostException) {
            return DNS_FAILURE;
        }

        String className = cause.getClass().getName();
        if (className.contains("PoolAcquireTimeout") || className.contains("PoolAcquirePendingLimit")) {
            return POOL_ACQUIRE_TIMEOUT;
        }
        if (className.contains("PrematureCloseException")) {
            return PREMATURE_CLOSE;
        }
        if (className.startsWith("javax.net.ssl") || className.contains("SSLHandshake")) {
            return TLS_FAILURE;
        }

        String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
        if (cause instanceof TimeoutException) {
            // Reactor's own timeout operator: the end-to-end request budget expired.
            return message.contains("pool") ? POOL_ACQUIRE_TIMEOUT : REQUEST_TIMEOUT;
        }
        if (cause instanceof IOException) {
            if (message.contains("connection reset") || message.contains("broken pipe")) {
                return CONNECTION_RESET;
            }
            if (message.contains("connection refused")) {
                return CONNECTION_REFUSED;
            }
            return CONNECTION_RESET;
        }
        if (message.contains("pending acquire") || message.contains("pool")) {
            return POOL_ACQUIRE_TIMEOUT;
        }
        return UNKNOWN;
    }

    /**
     * Converts a transport failure into the client-facing exception, preserving the
     * distinction between "no usable response" (502) and "no response in time" (504).
     *
     * <p>Pool acquire timeouts map to 503, not 504: nothing timed out on the backend's side,
     * the ALB simply had no capacity to talk to it. 503 is the honest answer and is the one
     * that tells an operator to look at ALB pool sizing.
     */
    public LoadBalancerException toException(Throwable error, String backendId) {
        if (error instanceof LoadBalancerException alreadyMapped) {
            return alreadyMapped;
        }
        String kind = classify(error);
        return switch (kind) {
            case CONNECT_TIMEOUT -> new BackendTimeoutException(backendId, "CONNECT",
                    "Timed out establishing a connection to the backend", error);
            case RESPONSE_TIMEOUT -> new BackendTimeoutException(backendId, "RESPONSE",
                    "Backend did not respond within the configured response timeout", error);
            case REQUEST_TIMEOUT -> new BackendTimeoutException(backendId, "REQUEST",
                    "Request exceeded the configured end-to-end timeout", error);
            case POOL_ACQUIRE_TIMEOUT -> new BackendPoolExhaustedException(backendId, error);
            default -> new BackendUnavailableException(backendId, kind,
                    "Backend is unavailable (" + kind + ")", error);
        };
    }

    /**
     * @return true if this failure means the request never reached a point where the backend
     *         could have acted on it, so it is safe to try another backend
     */
    public boolean isRetryable(Throwable error) {
        String kind = classify(error);
        return switch (kind) {
            case CONNECTION_REFUSED, CONNECTION_RESET, PREMATURE_CLOSE,
                 CONNECT_TIMEOUT, RESPONSE_TIMEOUT, POOL_ACQUIRE_TIMEOUT, DNS_FAILURE -> true;
            default -> false;
        };
    }

    /** Unwraps client-library wrappers to reach the transport-level cause. */
    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof WebClientRequestException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
