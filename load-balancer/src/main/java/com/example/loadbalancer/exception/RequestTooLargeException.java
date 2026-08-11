package com.example.loadbalancer.exception;

import org.springframework.http.HttpStatus;

/**
 * The client's request body exceeded the configured limit.
 *
 * <p>Enforced by the ALB rather than delegated to backends: an unbounded body streamed to
 * a backend still consumes ALB sockets and backend memory, and the whole point of a limit
 * is to reject early.
 */
public final class RequestTooLargeException extends LoadBalancerException {

    public RequestTooLargeException(long limitBytes) {
        super(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "Request body exceeds the configured limit of " + limitBytes + " bytes");
    }
}
