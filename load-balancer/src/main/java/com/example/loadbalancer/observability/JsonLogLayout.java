package com.example.loadbalancer.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.LayoutBase;

import java.time.Instant;
import java.util.Map;

/**
 * Renders log events as one JSON object per line.
 *
 * <h2>Why structured logs matter for a proxy specifically</h2>
 * The useful questions are all field queries: "p99 latency for backend-2 last hour", "every
 * request that got a 504", "which backend served request id X". Against a formatted string those
 * require regexes that break the first time a message is reworded. As JSON fields they are
 * indexable, and every MDC entry the {@link AccessLogger} sets becomes a first-class field.
 *
 * <h2>Why hand-written instead of logstash-logback-encoder</h2>
 * The dependency is perfectly good, but this is about sixty lines and removes a version to keep
 * aligned with Logback's own. It also keeps the escaping explicit, which matters more than it
 * sounds: a log field that fails to escape a quote produces malformed JSON, and a log pipeline
 * that silently drops malformed lines drops exactly the lines describing the incident.
 *
 * <p>Enabled by the {@code json-logs} Spring profile; the default profile uses a human-readable
 * console pattern, because reading JSON during local development is miserable.
 */
public class JsonLogLayout extends LayoutBase<ILoggingEvent> {

    @Override
    public String doLayout(ILoggingEvent event) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        field(json, "timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString(), true);
        field(json, "level", event.getLevel().toString(), false);
        field(json, "logger", event.getLoggerName(), false);
        field(json, "thread", event.getThreadName(), false);
        field(json, "message", event.getFormattedMessage(), false);

        // MDC entries carry the per-request access-log fields (requestId, backend, status, ...).
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) {
            for (Map.Entry<String, String> entry : mdc.entrySet()) {
                field(json, entry.getKey(), entry.getValue(), false);
            }
        }

        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            field(json, "exception", throwable.getClassName(), false);
            field(json, "exceptionMessage", throwable.getMessage(), false);
            // Full stack traces go to logs only, never to a client response.
            field(json, "stackTrace", ThrowableProxyUtil.asString(throwable), false);
        }
        json.append('}').append(System.lineSeparator());
        return json.toString();
    }

    private static void field(StringBuilder json, String name, String value, boolean first) {
        if (value == null) {
            return;
        }
        if (!first) {
            json.append(',');
        }
        json.append('"');
        escape(json, name);
        json.append("\":\"");
        escape(json, value);
        json.append('"');
    }

    /** Escapes per RFC 8259: quotes, backslash and every control character below 0x20. */
    private static void escape(StringBuilder out, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
    }
}
