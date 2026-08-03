package com.example.loadbalancer.config;

import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import com.example.loadbalancer.testsupport.TestBackends;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationValidatorTest {

    private static final List<LoadBalancerProperties.Backend> TWO_BACKENDS = List.of(
            TestBackends.backendConfig("backend-1", "host-1", 8080, 1),
            TestBackends.backendConfig("backend-2", "host-2", 8081, 1));

    @Test
    @DisplayName("accepts a valid configuration")
    void acceptsValidConfiguration() {
        assertThatCode(() -> ConfigurationValidator.validate(
                TestBackends.properties(LoadBalancingAlgorithm.ROUND_ROBIN, TWO_BACKENDS)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects duplicate backend ids")
    void rejectsDuplicateBackendIds() {
        LoadBalancerProperties properties = TestBackends.properties(
                LoadBalancingAlgorithm.ROUND_ROBIN, List.of(
                        TestBackends.backendConfig("same", "host-1", 8080, 1),
                        TestBackends.backendConfig("same", "host-2", 8081, 1)));

        assertThatThrownBy(() -> ConfigurationValidator.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate backend id 'same'");
    }

    @Test
    @DisplayName("rejects a route pointing at a backend that does not exist")
    void rejectsRouteWithUnknownBackend() {
        LoadBalancerProperties properties = TestBackends
                .propertiesBuilder(LoadBalancingAlgorithm.ROUND_ROBIN, TWO_BACKENDS)
                .routes(List.of(new LoadBalancerProperties.Route(
                        "orders", "/api/orders/**", Set.of(), List.of("backend-9"), null)))
                .build();

        // A route whose pool does not exist blackholes every request that matches it, silently.
        assertThatThrownBy(() -> ConfigurationValidator.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("references unknown backend 'backend-9'");
    }

    @Test
    @DisplayName("rejects a backend pointing at the load balancer's own listen address")
    void rejectsSelfReferentialBackend() {
        LoadBalancerProperties properties = TestBackends.properties(
                LoadBalancingAlgorithm.ROUND_ROBIN, List.of(
                        TestBackends.backendConfig("loop", "localhost", 8080, 1)));

        assertThatThrownBy(() -> ConfigurationValidator.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("infinite proxy loop");
    }

    @Test
    @DisplayName("rejects a request timeout shorter than the response timeout")
    void rejectsInvertedTimeouts() {
        LoadBalancerProperties properties = TestBackends
                .propertiesBuilder(LoadBalancingAlgorithm.ROUND_ROBIN, TWO_BACKENDS)
                .timeouts(Duration.ofSeconds(30), Duration.ofSeconds(5))
                .build();

        // The end-to-end budget would expire before one attempt could finish, so no request
        // could ever succeed.
        assertThatThrownBy(() -> ConfigurationValidator.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be >= timeouts.response");
    }

    @Test
    @DisplayName("rejects a circuit-breaker window larger than the bitset supports")
    void rejectsOversizedCircuitBreakerWindow() {
        LoadBalancerProperties properties = TestBackends
                .propertiesBuilder(LoadBalancingAlgorithm.ROUND_ROBIN, TWO_BACKENDS)
                .circuitBreaker(true, 100, 10, 50, Duration.ofSeconds(10), 3, 2)
                .build();

        assertThatThrownBy(() -> ConfigurationValidator.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sliding-window-size must be <= 64");
    }

    @Test
    @DisplayName("rejects minimum-calls larger than the window")
    void rejectsUnreachableMinimumCalls() {
        LoadBalancerProperties properties = TestBackends
                .propertiesBuilder(LoadBalancingAlgorithm.ROUND_ROBIN, TWO_BACKENDS)
                .circuitBreaker(true, 10, 20, 50, Duration.ofSeconds(10), 3, 2)
                .build();

        // The breaker could never accumulate enough calls to evaluate, so it would never open.
        assertThatThrownBy(() -> ConfigurationValidator.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be <= sliding-window-size");
    }

    @Test
    @DisplayName("rejects a half-open configuration that can never close the breaker")
    void rejectsUnclosableBreaker() {
        LoadBalancerProperties properties = TestBackends
                .propertiesBuilder(LoadBalancingAlgorithm.ROUND_ROBIN, TWO_BACKENDS)
                .circuitBreaker(true, 20, 10, 50, Duration.ofSeconds(10), 2, 5)
                .build();

        assertThatThrownBy(() -> ConfigurationValidator.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("can never close");
    }

    @Test
    @DisplayName("rejects an unsupported HTTP method in retry configuration")
    void rejectsUnknownRetryMethod() {
        LoadBalancerProperties properties = TestBackends
                .propertiesBuilder(LoadBalancingAlgorithm.ROUND_ROBIN, TWO_BACKENDS)
                .retry(true, 2, Set.of("GET", "FETCH"))
                .build();

        assertThatThrownBy(() -> ConfigurationValidator.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported HTTP method 'FETCH'");
    }

    @Test
    @DisplayName("reports every problem at once, not just the first")
    void reportsAllProblems() {
        LoadBalancerProperties properties = TestBackends
                .propertiesBuilder(LoadBalancingAlgorithm.ROUND_ROBIN, List.of(
                        TestBackends.backendConfig("dup", "host-1", 8080, 1),
                        TestBackends.backendConfig("dup", "host-2", 8081, 1)))
                .timeouts(Duration.ofSeconds(30), Duration.ofSeconds(5))
                .circuitBreaker(true, 100, 10, 50, Duration.ofSeconds(10), 3, 2)
                .build();

        assertThatThrownBy(() -> ConfigurationValidator.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(error -> {
                    String message = error.getMessage();
                    assertThat(message).contains("duplicate backend id");
                    assertThat(message).contains("timeouts.request");
                    assertThat(message).contains("sliding-window-size");
                    // Numbered list: an operator fixes everything in one pass instead of
                    // restarting to discover the next error.
                    assertThat(message).contains("1.").contains("2.").contains("3.");
                });
    }

    @Test
    @DisplayName("warns but does not fail on an ambiguous-but-legal configuration")
    void warnsOnRiskyButLegalConfiguration() {
        LoadBalancerProperties properties = TestBackends
                .propertiesBuilder(LoadBalancingAlgorithm.ROUND_ROBIN, TWO_BACKENDS)
                .retry(true, 2, Set.of("GET", "POST"))
                .build();

        // Retrying POST is a decision an operator is allowed to make; it is warned about, not
        // forbidden, because some POST endpoints genuinely are idempotent.
        assertThatCode(() -> ConfigurationValidator.validate(properties)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a route path that is not absolute")
    void rejectsRelativeRoutePath() {
        LoadBalancerProperties properties = TestBackends
                .propertiesBuilder(LoadBalancingAlgorithm.ROUND_ROBIN, TWO_BACKENDS)
                .routes(List.of(new LoadBalancerProperties.Route(
                        "bad", "api/users/**", Set.of(), List.of(), null)))
                .build();

        assertThatThrownBy(() -> ConfigurationValidator.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must start with '/'");
    }

    @Test
    @DisplayName("rejects duplicate route ids")
    void rejectsDuplicateRouteIds() {
        LoadBalancerProperties properties = TestBackends
                .propertiesBuilder(LoadBalancingAlgorithm.ROUND_ROBIN, TWO_BACKENDS)
                .routes(List.of(
                        new LoadBalancerProperties.Route("same", "/a/**", Set.of(), List.of(), null),
                        new LoadBalancerProperties.Route("same", "/b/**", Set.of(), List.of(), null)))
                .build();

        assertThatThrownBy(() -> ConfigurationValidator.validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate route id 'same'");
    }
}
