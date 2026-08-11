package com.example.loadbalancer.exception;

import org.springframework.http.HttpStatus;

/**
 * A selected backend could not serve the request: connection refused, connection reset,
 * TLS failure, pool acquisition failure or a malformed response.
 *
 * <p>Reported as 502 — the ALB is fine, its upstream is not. The backend id is carried on
 * the exception for logging and metrics but is never rendered into the client response,
 * since internal topology is not the caller's business.
 */
public final class BackendUnavailableException extends LoadBalancerException {

    private final String backendId;
    private final String failureKind;

    public BackendUnavailableException(String backendId, String failureKind, String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "BAD_GATEWAY", message, cause);
        this.backendId = backendId;
        this.failureKind = failureKind;
    }

    public String backendId() {
        return backendId;
    }

    /** @return a coarse cause label such as {@code CONNECTION_REFUSED}, used as a metric tag. */
    public String failureKind() {
        return failureKind;
    }
}
