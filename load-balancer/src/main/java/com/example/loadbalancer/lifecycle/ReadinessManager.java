package com.example.loadbalancer.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single source of truth for "should this instance receive traffic".
 *
 * <h2>Why readiness is separate from liveness</h2>
 * During a graceful shutdown the ALB is perfectly alive — it is still finishing requests —
 * but it must stop being sent new ones. Conflating the two states means the orchestrator either
 * kills the process while requests are in flight, or keeps routing to a process that is about
 * to exit. Both produce client-visible errors during what should be a zero-downtime deploy.
 *
 * <p>The flag is flipped <em>before</em> the listener is closed, and it must stay flipped for
 * at least one upstream health-check interval before the socket goes away. That gap is the
 * whole trick behind a drain: the upstream load balancer or Kubernetes endpoint controller
 * needs time to observe "not ready" and stop sending new connections. Closing the socket the
 * instant SIGTERM arrives produces connection-refused errors for anything already in flight
 * upstream, which is the most common cause of 502s during a rolling deploy.
 *
 * <p>State is also published as a Spring Boot {@link AvailabilityChangeEvent}, so
 * {@code /actuator/health/readiness} reports it without any extra wiring — which is what a
 * Kubernetes readiness probe should point at.
 */
@Component
public class ReadinessManager {

    private static final Logger log = LoggerFactory.getLogger(ReadinessManager.class);

    private final ApplicationEventPublisher eventPublisher;
    private final ApplicationAvailability availability;
    private final AtomicBoolean acceptingTraffic = new AtomicBoolean(true);

    public ReadinessManager(ApplicationEventPublisher eventPublisher, ApplicationAvailability availability) {
        this.eventPublisher = eventPublisher;
        this.availability = availability;
    }

    /** @return true if new requests should be accepted. Read on every request. */
    public boolean isAcceptingTraffic() {
        return acceptingTraffic.get();
    }

    /**
     * Marks the instance not ready. Idempotent, and safe to call from a shutdown hook.
     *
     * @param reason recorded in the log so the transition is explainable after the fact
     */
    public void stopAcceptingTraffic(String reason) {
        if (acceptingTraffic.compareAndSet(true, false)) {
            log.info("No longer accepting new requests: {}", reason);
            AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);
        }
    }

    /** Returns the instance to service. Used by tests and by manual operator intervention. */
    public void startAcceptingTraffic() {
        if (acceptingTraffic.compareAndSet(false, true)) {
            log.info("Accepting new requests again");
            AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    /** @return the readiness state Spring Boot's actuator is currently reporting. */
    public ReadinessState readinessState() {
        return availability.getState(ReadinessState.class);
    }
}
