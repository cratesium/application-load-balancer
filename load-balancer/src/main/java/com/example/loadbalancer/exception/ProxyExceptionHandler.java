package com.example.loadbalancer.exception;

import com.example.loadbalancer.config.LoadBalancerProperties;
import com.example.loadbalancer.model.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Renders every unhandled failure as a small, uniform JSON document.
 *
 * <h2>What a client is allowed to learn</h2>
 * The status, a stable error code, a short message and the request id. Never a stack trace,
 * never an internal hostname, never which backend failed. Stack traces from a proxy are a
 * reconnaissance gift — they disclose library versions, internal class names and often
 * internal addresses — and the backend id tells an attacker the shape of the topology behind
 * the ALB. Operators get the full detail in the logs, correlated by the same request id.
 *
 * <h2>Committed responses</h2>
 * If the failure happened after the response was already committed — a backend dying
 * mid-stream — there is nothing useful to do. The status line and headers are gone and the
 * client has already received a partial body. The connection is closed so the client sees a
 * truncated response and treats it as an error, rather than a well-formed but silently
 * incomplete one.
 *
 * <p>Registered with high precedence so it wins over Spring Boot's default error handling,
 * which would otherwise render an HTML error page for a proxy that only ever speaks JSON.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProxyExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProxyExceptionHandler.class);

    private final ObjectMapper objectMapper;
    private final String requestIdHeader;

    public ProxyExceptionHandler(ObjectMapper objectMapper, LoadBalancerProperties properties) {
        this.objectMapper = objectMapper;
        this.requestIdHeader = properties.proxy().requestIdHeader();
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable throwable) {
        ServerHttpResponse response = exchange.getResponse();
        String requestId = requestId(exchange);

        if (response.isCommitted()) {
            log.warn("Request {} failed after the response was committed; closing the connection: {}",
                    requestId, throwable.toString());
            return response.setComplete();
        }

        ErrorDescriptor descriptor = describe(throwable);
        if (descriptor.status().is5xxServerError()) {
            log.error("Request {} failed: {} {}", requestId, descriptor.errorCode(), descriptor.message(), throwable);
        } else {
            log.debug("Request {} rejected: {} {}", requestId, descriptor.errorCode(), descriptor.message());
        }

        response.setStatusCode(descriptor.status());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set(requestIdHeader, requestId);
        if (descriptor.status() == HttpStatus.SERVICE_UNAVAILABLE) {
            // Tell well-behaved clients and CDNs roughly when to come back rather than
            // leaving them to retry immediately and add to the overload.
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
        }

        ErrorResponse body = ErrorResponse.of(
                descriptor.status().value(), descriptor.errorCode(), descriptor.message(), requestId);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception ex) {
            // Serialising four strings cannot realistically fail, but an error handler that
            // can itself throw is how a 500 becomes a hung connection.
            bytes = ("{\"status\":" + descriptor.status().value()
                    + ",\"error\":\"" + descriptor.errorCode() + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer))
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
    }

    /** Status, code and safe message for a throwable. */
    private record ErrorDescriptor(HttpStatus status, String errorCode, String message) {
    }

    private ErrorDescriptor describe(Throwable throwable) {
        if (throwable instanceof LoadBalancerException lbe) {
            // The hierarchy already carries its own mapping, so there is no switch to keep
            // in sync as failure modes are added.
            return new ErrorDescriptor(lbe.status(), lbe.errorCode(), lbe.getMessage());
        }
        if (throwable instanceof ResponseStatusException rse) {
            HttpStatus status = HttpStatus.resolve(rse.getStatusCode().value());
            HttpStatus resolved = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
            return new ErrorDescriptor(resolved, resolved.name(), resolved.getReasonPhrase());
        }
        if (throwable instanceof IllegalArgumentException) {
            return new ErrorDescriptor(HttpStatus.BAD_REQUEST, "BAD_REQUEST", throwable.getMessage());
        }
        return new ErrorDescriptor(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "The load balancer encountered an unexpected error");
    }

    private String requestId(ServerWebExchange exchange) {
        String fromResponse = exchange.getResponse().getHeaders().getFirst(requestIdHeader);
        if (fromResponse != null) {
            return fromResponse;
        }
        String fromRequest = exchange.getRequest().getHeaders().getFirst(requestIdHeader);
        return fromRequest != null ? fromRequest : "unknown";
    }
}
