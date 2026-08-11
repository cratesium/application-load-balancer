package com.example.loadbalancer.metrics;

import com.example.loadbalancer.backend.BackendChangeEvent;
import com.example.loadbalancer.backend.BackendRegistry;
import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.backend.BackendState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Per-backend metrics: counters, timers and live gauges.
 *
 * <h2>Gauge lifecycle</h2>
 * Gauges are registered when a backend joins the pool and <em>unregistered</em> when it
 * leaves. Skipping the removal is a common and expensive mistake: Micrometer holds a strong
 * reference to the gauge's source object, so every backend the ALB has ever seen would stay
 * in the registry and in the scrape output forever. In an autoscaled environment that is an
 * unbounded leak of both memory and Prometheus series.
 *
 * <p>Gauges observe the backend object directly rather than being pushed values, so
 * {@code loadbalancer_backend_active_connections} is read from the same
 * {@code AtomicInteger} that least-connections routing uses. There is no separate counter to
 * drift out of sync — what the dashboard shows is exactly what the router decided on.
 */
@Component
public class BackendMetrics {

    private final MeterRegistry registry;
    private final BackendRegistry backendRegistry;

    /** Meters owned per backend id, so they can be removed with the backend. */
    private final Map<String, List<Meter.Id>> ownedMeters = new ConcurrentHashMap<>();

    public BackendMetrics(MeterRegistry registry, BackendRegistry backendRegistry) {
        this.registry = registry;
        this.backendRegistry = backendRegistry;
    }

    @PostConstruct
    void subscribeToBackendChanges() {
        // addListener replays the current membership, so backends registered before this
        // component existed still get their gauges.
        backendRegistry.addListener(this::onBackendChange);
    }

    private void onBackendChange(BackendChangeEvent event) {
        switch (event.type()) {
            case ADDED -> registerGauges(event.backend());
            case REMOVED -> unregisterGauges(event.backend().id());
            default -> {
                // State and weight changes are reflected by the gauges automatically.
            }
        }
    }

    private void registerGauges(BackendServer backend) {
        if (ownedMeters.containsKey(backend.id())) {
            return;
        }
        Tags tags = Tags.of("backend", backend.id());
        List<Meter.Id> meters = new ArrayList<>(4);

        meters.add(Gauge.builder("loadbalancer_backend_active_connections", backend, BackendServer::activeConnections)
                .description("Requests currently in flight to this backend")
                .tags(tags)
                .register(registry).getId());

        meters.add(Gauge.builder("loadbalancer_backend_health_status", backend,
                        b -> b.state() == BackendState.UP ? 1.0 : 0.0)
                .description("1 when the backend is UP and eligible for routing, 0 otherwise")
                .tags(tags)
                .register(registry).getId());

        meters.add(Gauge.builder("loadbalancer_backend_weight", backend, BackendServer::weight)
                .description("Configured routing weight")
                .tags(tags)
                .register(registry).getId());

        ownedMeters.put(backend.id(), meters);
    }

    private void unregisterGauges(String backendId) {
        List<Meter.Id> meters = ownedMeters.remove(backendId);
        if (meters != null) {
            meters.forEach(registry::remove);
        }
    }

    /**
     * Records one attempt against a backend.
     *
     * @param status HTTP status returned by the backend
     */
    public void recordBackendRequest(String backendId, String method, int status, long durationNs) {
        Counter.builder("loadbalancer_backend_requests_total")
                .description("Requests forwarded to a backend")
                .tag("backend", backendId)
                .tag("status", String.valueOf(status))
                .register(registry)
                .increment();

        Timer.builder("loadbalancer_backend_response_time_seconds")
                .description("Time for a backend to serve a forwarded request")
                .publishPercentileHistogram()
                .tag("backend", backendId)
                .tag("status", String.valueOf(status))
                .register(registry)
                .record(durationNs, TimeUnit.NANOSECONDS);
    }

    /**
     * Records a backend attempt that failed at the transport level.
     *
     * @param kind coarse failure classification, e.g. {@code CONNECTION_REFUSED}
     */
    public void recordBackendFailure(String backendId, String kind, long durationNs) {
        Counter.builder("loadbalancer_backend_failures_total")
                .description("Backend attempts that failed before a usable response was received")
                .tag("backend", backendId)
                .tag("kind", kind)
                .register(registry)
                .increment();

        Timer.builder("loadbalancer_backend_response_time_seconds")
                .publishPercentileHistogram()
                .tag("backend", backendId)
                .tag("status", "error")
                .register(registry)
                .record(durationNs, TimeUnit.NANOSECONDS);
    }
}
