package com.example.loadbalancer.exception;

import org.springframework.http.HttpStatus;

/**
 * The ALB is already carrying its configured maximum number of in-flight requests.
 *
 * <p>Shedding load with a fast 503 is the correct behaviour for a reactive proxy: without
 * a ceiling, "non-blocking" simply means the queue grows in heap instead of in a thread
 * pool, and the failure mode becomes an OOM that takes out every in-flight request rather
 * than a bounded number of rejections.
 */
public final class OverloadedException extends LoadBalancerException {

    public OverloadedException(int limit, int current) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "OVERLOADED",
                "Load balancer is at capacity (" + current + "/" + limit + " concurrent requests)");
    }
}
