package com.example.loadbalancer.config;

import com.example.loadbalancer.proxy.ConcurrencyLimiter;
import com.example.loadbalancer.resilience.CircuitBreakerRegistry;
import com.example.loadbalancer.routing.AlgorithmManager;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Metrics registry configuration: common tags, histogram settings, cardinality guards and
 * process-level gauges.
 *
 * <h2>Common tags</h2>
 * Every meter carries {@code application} and {@code instance}. Without an instance tag,
 * scraping several ALB replicas produces series that silently overwrite or aggregate in
 * confusing ways, and "which instance is slow" becomes unanswerable.
 *
 * <h2>Histograms, and why not on everything</h2>
 * Percentile histograms are enabled for request and backend latency because p99 is the number
 * that matters for a proxy — an average hides exactly the tail you are paid to care about. They
 * are <em>not</em> enabled globally: each histogram is dozens of extra time series per tag
 * combination, and turning them on for every timer in the JVM is a reliable way to overwhelm a
 * Prometheus server.
 *
 * <p>The service-level objective buckets are chosen around proxy-relevant thresholds: single
 * -digit milliseconds (ALB overhead only), tens of milliseconds (normal backend), and the
 * seconds range where timeouts live.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfig {

    /** Bucket boundaries for latency histograms, in the range a proxy actually operates in. */
    private static final Duration[] LATENCY_SLOS = {
            Duration.ofMillis(1),
            Duration.ofMillis(5),
            Duration.ofMillis(10),
            Duration.ofMillis(25),
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            Duration.ofMillis(250),
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10)
    };

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags(
            @Value("${spring.application.name:load-balancer}") String applicationName,
            @Value("${ALB_INSTANCE_ID:${HOSTNAME:local}}") String instanceId) {
        return registry -> registry.config()
                .commonTags("application", applicationName, "instance", instanceId)
                .meterFilter(latencyHistograms())
                // Backstop against a tagging mistake shipping a cardinality explosion to
                // production: cap the number of series any single metric name can create.
                .meterFilter(MeterFilter.maximumAllowableTags(
                        "loadbalancer_requests_total", "route", 200, MeterFilter.deny()))
                .meterFilter(MeterFilter.maximumAllowableTags(
                        "loadbalancer_backend_requests_total", "backend", 500, MeterFilter.deny()));
    }

    private MeterFilter latencyHistograms() {
        return new MeterFilter() {
            @Override
            public io.micrometer.core.instrument.distribution.DistributionStatisticConfig configure(
                    io.micrometer.core.instrument.Meter.Id id,
                    io.micrometer.core.instrument.distribution.DistributionStatisticConfig config) {
                if (id.getName().startsWith("loadbalancer_") && id.getName().endsWith("_seconds")) {
                    return io.micrometer.core.instrument.distribution.DistributionStatisticConfig.builder()
                            .percentilesHistogram(true)
                            .serviceLevelObjectives(toNanos(LATENCY_SLOS))
                            .build()
                            .merge(config);
                }
                return config;
            }
        };
    }

    private static double[] toNanos(Duration[] durations) {
        double[] values = new double[durations.length];
        for (int i = 0; i < durations.length; i++) {
            values[i] = durations[i].toNanos();
        }
        return values;
    }

    /**
     * Process-level gauges that do not belong to any one component.
     *
     * <p>{@code loadbalancer_algorithm_active} follows the Prometheus "enum metric" pattern:
     * one series per algorithm, valued 1 for the active one and 0 for the rest. A single gauge
     * tagged with the algorithm name would be wrong — the tag is fixed when the meter is
     * registered, so it would keep reporting the algorithm that was active at startup even
     * after a hot swap. This shape lets a dashboard annotate the exact moment of a switch,
     * which is what you want when comparing latency before and after one.
     */
    @Bean
    public MeterBinderRegistration loadBalancerGauges(MeterRegistry registry,
                                                      ConcurrencyLimiter concurrencyLimiter,
                                                      CircuitBreakerRegistry circuitBreakers,
                                                      AlgorithmManager algorithmManager) {
        Gauge.builder("loadbalancer_concurrency_limit", concurrencyLimiter, ConcurrencyLimiter::limit)
                .description("Configured maximum number of in-flight requests")
                .register(registry);
        Gauge.builder("loadbalancer_concurrency_in_flight", concurrencyLimiter, ConcurrencyLimiter::inFlight)
                .description("Requests currently admitted and in flight")
                .register(registry);
        Gauge.builder("loadbalancer_circuit_breakers_open", circuitBreakers,
                        CircuitBreakerRegistry::openCircuitCount)
                .description("Number of backends whose circuit breaker is currently OPEN")
                .register(registry);
        for (com.example.loadbalancer.model.LoadBalancingAlgorithm algorithm
                : com.example.loadbalancer.model.LoadBalancingAlgorithm.values()) {
            Gauge.builder("loadbalancer_algorithm_active", algorithmManager,
                            manager -> manager.current() == algorithm ? 1.0 : 0.0)
                    .description("1 for the currently active load balancing algorithm, 0 otherwise")
                    .tag("algorithm", algorithm.name())
                    .register(registry);
        }
        return new MeterBinderRegistration();
    }

    /** Marker bean; exists so the gauge registration above participates in the context lifecycle. */
    public static final class MeterBinderRegistration {
    }
}
