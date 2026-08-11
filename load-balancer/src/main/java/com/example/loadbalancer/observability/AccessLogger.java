package com.example.loadbalancer.observability;

import com.example.loadbalancer.proxy.ProxyRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits one structured line per proxied request.
 *
 * <h2>What is deliberately never logged</h2>
 * Headers and bodies are not logged — not selectively redacted, not truncated,
 * <em>not logged at all</em>. Redaction lists are a losing game: they need updating for every
 * new auth scheme, they miss the token someone put in a query parameter, and a single miss
 * writes a credential into a log aggregator that a much wider group of people can read than
 * can read production data. So {@code Authorization}, {@code Cookie}, API keys, tokens and
 * request bodies never enter this code path. The request id is what links an ALB log line to
 * a backend log line that <em>does</em> have application context.
 *
 * <p>Query strings are logged only as a boolean presence flag, because query parameters
 * routinely carry tokens and personal data.
 *
 * <h2>Why fields go through MDC</h2>
 * The fields are put in the MDC around one synchronous log call, so the JSON encoder can
 * render them as real JSON fields rather than a pre-formatted string. This makes them
 * queryable in a log aggregator ({@code backend:"backend-2" AND status:504}) instead of
 * requiring a regex over a message. The MDC is cleared immediately afterwards — leaving
 * entries behind on a pooled event-loop thread would attach one request's id to an unrelated
 * later request, which is worse than having no id at all.
 */
@Component
public class AccessLogger {

    private static final Logger log = LoggerFactory.getLogger("com.example.loadbalancer.access");

    /** Logs a request that completed, successfully or not. */
    public void logSuccess(ProxyRequestContext context, int status, long durationNanos) {
        Map<String, String> fields = baseFields(context, durationNanos);
        fields.put("status", String.valueOf(status));
        withFields(fields, () -> {
            if (status >= 500) {
                log.warn("request completed with server error");
            } else {
                log.info("request completed");
            }
        });
    }

    /** Logs a request the ALB itself failed. */
    public void logFailure(ProxyRequestContext context, int status, String errorCode,
                           String failureKind, long durationNanos) {
        Map<String, String> fields = baseFields(context, durationNanos);
        fields.put("status", String.valueOf(status));
        fields.put("error", errorCode);
        if (failureKind != null) {
            fields.put("errorKind", failureKind);
        }
        withFields(fields, () -> log.warn("request failed"));
    }

    /** Logs an attempt that failed but will be retried against another backend. */
    public void logRetry(ProxyRequestContext context, String failedBackendId, String reason) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("requestId", context.requestId());
        fields.put("method", context.method());
        fields.put("path", context.rawPath());
        fields.put("backend", failedBackendId);
        fields.put("attempt", String.valueOf(context.attempts()));
        fields.put("reason", reason);
        withFields(fields, () -> log.warn("retrying request on another backend"));
    }

    private Map<String, String> baseFields(ProxyRequestContext context, long durationNanos) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("requestId", context.requestId());
        fields.put("method", context.method());
        fields.put("path", context.rawPath());
        fields.put("hasQuery", String.valueOf(context.rawQuery() != null && !context.rawQuery().isEmpty()));
        fields.put("route", context.routeId());
        fields.put("algorithm", context.algorithm());
        fields.put("retryCount", String.valueOf(context.retryCount()));
        fields.put("durationMs", String.valueOf(durationNanos / 1_000_000));
        if (context.clientIp() != null) {
            fields.put("clientIp", context.clientIp());
        }
        if (context.currentBackend() != null) {
            fields.put("backend", context.currentBackend().id());
            fields.put("backendHost", context.currentBackend().host());
            fields.put("backendPort", String.valueOf(context.currentBackend().port()));
        }
        return fields;
    }

    private void withFields(Map<String, String> fields, Runnable logging) {
        fields.forEach(MDC::put);
        try {
            logging.run();
        } finally {
            fields.keySet().forEach(MDC::remove);
        }
    }
}
