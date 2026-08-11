package com.example.loadbalancer.exception;

import org.springframework.http.HttpStatus;

/**
 * The ALB could not obtain a connection from a backend's pool within
 * {@code connection-pool.pending-acquire-timeout}.
 *
 * <p>Reported as 503 rather than 502 or 504 on purpose. Nothing is wrong with the backend
 * and nothing on the backend timed out — the load balancer itself ran out of connections to
 * it. Reporting this as a backend fault sends an operator to inspect a healthy server; 503
 * with a distinct error code points at ALB pool sizing, which is where the fix is.
 */
public final class BackendPoolExhaustedException extends LoadBalancerException {

    private final String backendId;

    public BackendPoolExhaustedException(String backendId, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "POOL_EXHAUSTED",
                "No backend connection could be acquired before the pool timeout expired", cause);
        this.backendId = backendId;
    }

    public String backendId() {
        return backendId;
    }
}
