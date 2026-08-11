package com.example.loadbalancer.model;

import java.time.Instant;

/**
 * The only error shape the ALB ever returns.
 *
 * <p>Contains no stack trace, no backend identity and no internal hostname — a client
 * learns that the request failed and gets a request id to quote in a support ticket, and
 * nothing about the topology behind the proxy.
 *
 * @param status    HTTP status code, repeated in the body for clients that log bodies only
 * @param error     stable machine-readable code, e.g. {@code GATEWAY_TIMEOUT}
 * @param message   short human-readable explanation, safe to show to a caller
 * @param requestId correlates this response with the ALB access log and backend logs
 */
public record ErrorResponse(int status, String error, String message, String requestId, Instant timestamp) {

    public static ErrorResponse of(int status, String error, String message, String requestId) {
        return new ErrorResponse(status, error, message, requestId, Instant.now());
    }
}
