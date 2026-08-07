package com.example.loadbalancer.exception;

import org.springframework.http.HttpStatus;

/**
 * Base of the load balancer's error hierarchy.
 *
 * <p>Every failure the ALB itself generates carries the HTTP status and the stable
 * machine-readable error code it should be reported as. Mapping lives with the failure
 * rather than in a big {@code if/else} in the handler, so adding a failure mode cannot
 * accidentally produce a 500.
 *
 * <p>Sealed so the complete set of ALB-generated failures is visible in one place and the
 * compiler can check exhaustive handling.
 */
public sealed abstract class LoadBalancerException extends RuntimeException
        permits NoHealthyBackendException,
                BackendUnavailableException,
                BackendTimeoutException,
                BackendPoolExhaustedException,
                RequestTooLargeException,
                OverloadedException,
                InvalidRequestException {

    private final HttpStatus status;
    private final String errorCode;

    protected LoadBalancerException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, null);
    }

    protected LoadBalancerException(HttpStatus status, String errorCode, String message, Throwable cause) {
        // Stack traces are never shown to clients and these are control-flow signals on a
        // hot path, so suppress writableStackTrace to avoid the fill-in cost per failure.
        super(message, cause, false, false);
        this.status = status;
        this.errorCode = errorCode;
    }

    /** @return the HTTP status this failure should be reported to the client as. */
    public HttpStatus status() {
        return status;
    }

    /** @return a stable, machine-readable code such as {@code NO_HEALTHY_BACKEND}. */
    public String errorCode() {
        return errorCode;
    }
}
