package com.example.loadbalancer.lifecycle;

import com.example.loadbalancer.config.LoadBalancerProperties;
import com.example.loadbalancer.metrics.LoadBalancerMetrics;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import com.example.loadbalancer.testsupport.TestBackends;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Graceful shutdown tests.
 *
 * <p>{@code stop()} is invoked here directly, which is exactly what SIGTERM causes: the signal
 * closes the Spring context, and the context stops {@link org.springframework.context.SmartLifecycle}
 * beans in descending phase order. That the signal reaches the context is Spring Boot's
 * contract; what this class owns — mark not-ready first, then wait for in-flight requests,
 * bounded by the grace period — is what is verified.
 */
class GracefulShutdownHandlerTest {

    private record Fixture(GracefulShutdownHandler handler,
                           ReadinessManager readiness,
                           LoadBalancerMetrics metrics,
                           List<ApplicationEvent> events) {
    }

    private Fixture fixture(Duration gracePeriod) {
        List<ApplicationEvent> events = new CopyOnWriteArrayList<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof ApplicationEvent applicationEvent) {
                events.add(applicationEvent);
            }
        };
        ApplicationAvailability availability = new ApplicationAvailabilityBean();
        ReadinessManager readiness = new ReadinessManager(publisher, availability);
        LoadBalancerMetrics metrics = new LoadBalancerMetrics(new SimpleMeterRegistry());

        LoadBalancerProperties properties = TestBackends
                .propertiesBuilder(LoadBalancingAlgorithm.ROUND_ROBIN,
                        List.of(TestBackends.backendConfig("backend-1", "host-1", 8080, 1)))
                .build();
        LoadBalancerProperties withGrace = new LoadBalancerProperties(
                properties.listen(), properties.algorithm(), properties.backends(),
                properties.routes(), properties.consistentHash(), properties.healthCheck(),
                properties.passiveHealth(), properties.timeouts(), properties.connectionPool(),
                properties.retry(), properties.circuitBreaker(), properties.draining(),
                new LoadBalancerProperties.Shutdown(gracePeriod),
                properties.limits(), properties.admin(), properties.proxy());

        return new Fixture(
                new GracefulShutdownHandler(readiness, metrics, withGrace),
                readiness, metrics, events);
    }

    @Test
    @DisplayName("starts in the accepting-traffic state")
    void startsAcceptingTraffic() {
        Fixture fixture = fixture(Duration.ofSeconds(5));
        fixture.handler().start();

        assertThat(fixture.handler().isRunning()).isTrue();
        assertThat(fixture.readiness().isAcceptingTraffic()).isTrue();
    }

    @Test
    @DisplayName("stops accepting traffic and publishes REFUSING_TRAFFIC before waiting")
    void marksNotReadyFirst() {
        Fixture fixture = fixture(Duration.ofMillis(100));
        fixture.handler().start();

        fixture.handler().stop();

        assertThat(fixture.readiness().isAcceptingTraffic()).isFalse();
        assertThat(fixture.handler().isRunning()).isFalse();
        // The readiness event is what makes an upstream load balancer or Kubernetes endpoint
        // controller stop sending new connections. Publishing it *before* the listener closes is
        // what prevents connection-refused errors during a rolling deploy.
        assertThat(fixture.events()).anySatisfy(event -> {
            assertThat(event).isInstanceOf(
                    org.springframework.boot.availability.AvailabilityChangeEvent.class);
            Object state = ((org.springframework.boot.availability.AvailabilityChangeEvent<?>) event).getState();
            assertThat(state).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
        });
    }

    @Test
    @DisplayName("returns immediately when nothing is in flight")
    void returnsImmediatelyWhenIdle() {
        Fixture fixture = fixture(Duration.ofSeconds(30));
        fixture.handler().start();

        long start = System.nanoTime();
        fixture.handler().stop();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // Only the readiness-propagation pause, nowhere near the 30s grace period.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("waits for an in-flight request to finish before completing")
    void waitsForInFlightRequests() throws Exception {
        Fixture fixture = fixture(Duration.ofSeconds(20));
        fixture.handler().start();
        fixture.metrics().incrementActive();

        CountDownLatch stopReturned = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            pool.submit(() -> {
                fixture.handler().stop();
                stopReturned.countDown();
            });

            // stop() must still be waiting while a request is outstanding.
            assertThat(stopReturned.await(2, TimeUnit.SECONDS))
                    .as("stop() must not return while a request is in flight")
                    .isFalse();

            fixture.metrics().decrementActive();

            assertThat(stopReturned.await(10, TimeUnit.SECONDS))
                    .as("stop() should return once the last request completes")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("gives up after the grace period rather than hanging forever")
    void givesUpAfterGracePeriod() {
        Fixture fixture = fixture(Duration.ofSeconds(1));
        fixture.handler().start();
        // A request that never completes — a long-lived stream, or one that is simply stuck.
        fixture.metrics().incrementActive();

        long start = System.nanoTime();
        fixture.handler().stop();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // Bounded: the deploy proceeds. Those requests are terminated, and the handler logs
        // that fact rather than reporting a clean drain.
        assertThat(elapsed).isBetween(Duration.ofMillis(900), Duration.ofSeconds(8));
    }

    @Test
    @DisplayName("stops before the web server, so the listener outlives the drain")
    void stopsBeforeWebServer() {
        Fixture fixture = fixture(Duration.ofSeconds(5));

        // Spring stops SmartLifecycle beans in descending phase order and the web server lives
        // in a high phase, so the lowest possible phase means this drains first. Getting this
        // backwards is the classic cause of 502s during a rolling deploy.
        assertThat(fixture.handler().getPhase()).isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    @DisplayName("readiness can be restored, for a manually paused instance")
    void readinessIsReversible() {
        Fixture fixture = fixture(Duration.ofSeconds(1));

        fixture.readiness().stopAcceptingTraffic("manual pause");
        assertThat(fixture.readiness().isAcceptingTraffic()).isFalse();

        fixture.readiness().startAcceptingTraffic();
        assertThat(fixture.readiness().isAcceptingTraffic()).isTrue();
    }

    @Test
    @DisplayName("marking not-ready twice is idempotent")
    void stopAcceptingIsIdempotent() {
        Fixture fixture = fixture(Duration.ofSeconds(1));

        fixture.readiness().stopAcceptingTraffic("first");
        int eventsAfterFirst = fixture.events().size();
        fixture.readiness().stopAcceptingTraffic("second");

        assertThat(fixture.events()).hasSize(eventsAfterFirst);
    }
}
