package com.example.loadbalancer.proxy;

import com.example.loadbalancer.config.LoadBalancerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounds the number of client requests in flight, shedding the excess.
 *
 * <h2>Why a non-blocking server still needs a limit</h2>
 * "Non-blocking" removes the thread-per-request ceiling; it does not create infinite
 * capacity. Without a bound, an overloaded ALB accepts every connection and every request
 * simply waits — in heap. Each pending request holds its buffers, headers, context object and
 * a slot in a backend's pending-acquire queue. The queue grows, latency grows with it, and
 * the failure mode is a heap exhaustion that kills every in-flight request at once, including
 * the ones that were about to succeed.
 *
 * <p>Shedding early converts that cliff into a slope. A fast 503 tells the client to back off
 * while the requests already admitted still complete on time. This is the "bounded queue"
 * principle: it is better to reject 10% of requests immediately than to make 100% of them
 * time out.
 *
 * <h2>Implementation</h2>
 * One {@link AtomicInteger} with a CAS-based bounded increment. A {@code Semaphore} would be
 * the obvious choice but its blocking acquire has no place on an event loop, and its
 * {@code tryAcquire} gives nothing extra over this. Release is clamped at zero so that a
 * double-release — always possible somewhere in a reactive chain — degrades into a slightly
 * inaccurate gauge rather than a permanently unusable limiter.
 */
@Component
public class ConcurrencyLimiter {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyLimiter.class);

    private final int maxConcurrentRequests;
    private final AtomicInteger inFlight = new AtomicInteger();

    public ConcurrencyLimiter(LoadBalancerProperties properties) {
        this.maxConcurrentRequests = properties.limits().maxConcurrentRequests();
        log.info("Concurrency limit: {} in-flight requests before load shedding", maxConcurrentRequests);
    }

    /**
     * Attempts to admit a request.
     *
     * @return true if admitted; the caller must then call {@link #release()} exactly once
     */
    public boolean tryAcquire() {
        while (true) {
            int current = inFlight.get();
            if (current >= maxConcurrentRequests) {
                return false;
            }
            if (inFlight.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Releases an admitted request. Safe to call more often than acquired. */
    public void release() {
        inFlight.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    public int inFlight() {
        return inFlight.get();
    }

    public int limit() {
        return maxConcurrentRequests;
    }
}
