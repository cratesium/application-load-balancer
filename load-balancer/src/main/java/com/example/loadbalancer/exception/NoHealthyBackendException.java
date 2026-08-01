package com.example.loadbalancer.exception;

import org.springframework.http.HttpStatus;

/**
 * No backend was eligible to serve the request.
 *
 * <p>Reported as 503 rather than 502: the ALB never reached a backend, so nothing
 * upstream returned a bad response. 503 also tells well-behaved clients and CDNs that
 * retrying later is reasonable.
 */
public final class NoHealthyBackendException extends LoadBalancerException {

    public NoHealthyBackendException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "NO_HEALTHY_BACKEND", message);
    }

    public static NoHealthyBackendException forRoute(String route, int totalBackends) {
        return new NoHealthyBackendException(
                "No healthy backend servers are currently available"
                        + (route == null ? "" : " for route '" + route + "'")
                        + " (" + totalBackends + " backend(s) in pool)");
    }
}
