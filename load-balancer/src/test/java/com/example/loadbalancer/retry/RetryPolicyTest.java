package com.example.loadbalancer.retry;

import com.example.loadbalancer.config.LoadBalancerProperties;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import com.example.loadbalancer.proxy.FailureClassifier;
import com.example.loadbalancer.testsupport.TestBackends;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final FailureClassifier classifier = new FailureClassifier();

    private RetryPolicy policy(boolean enabled, int maxAttempts, Set<String> methods) {
        LoadBalancerProperties properties = TestBackends
                .propertiesBuilder(LoadBalancingAlgorithm.ROUND_ROBIN,
                        List.of(TestBackends.backendConfig("backend-1", "host-1", 8080, 1)))
                .retry(enabled, maxAttempts, methods)
                .build();
        return new RetryPolicy(properties, classifier);
    }

    private RetryPolicy defaultPolicy() {
        return policy(true, 2, Set.of("GET", "HEAD", "OPTIONS"));
    }

    @Test
    @DisplayName("retries idempotent methods by default")
    void retriesIdempotentMethods() {
        RetryPolicy policy = defaultPolicy();

        assertThat(policy.isMethodRetryable("GET")).isTrue();
        assertThat(policy.isMethodRetryable("HEAD")).isTrue();
        assertThat(policy.isMethodRetryable("OPTIONS")).isTrue();
    }

    @Test
    @DisplayName("does NOT retry POST or PATCH by default")
    void doesNotRetryNonIdempotentMethods() {
        RetryPolicy policy = defaultPolicy();

        // A POST that timed out may already have been processed. Retrying it can charge a
        // customer twice, which is worse than returning an error the client can act on.
        assertThat(policy.isMethodRetryable("POST")).isFalse();
        assertThat(policy.isMethodRetryable("PATCH")).isFalse();
        assertThat(policy.isMethodRetryable("PUT")).isFalse();
        assertThat(policy.isMethodRetryable("DELETE")).isFalse();
    }

    @Test
    @DisplayName("retries POST only when an operator explicitly opts in")
    void retriesPostWhenConfigured() {
        RetryPolicy policy = policy(true, 2, Set.of("GET", "POST"));

        assertThat(policy.isMethodRetryable("POST")).isTrue();
    }

    @Test
    @DisplayName("method matching is case-insensitive")
    void methodMatchingIsCaseInsensitive() {
        assertThat(defaultPolicy().isMethodRetryable("get")).isTrue();
    }

    @Test
    @DisplayName("retries only upstream-failure statuses, never 4xx and never 500")
    void retriesOnlyUpstreamStatuses() {
        RetryPolicy policy = defaultPolicy();

        assertThat(policy.isStatusRetryable(502)).isTrue();
        assertThat(policy.isStatusRetryable(503)).isTrue();
        assertThat(policy.isStatusRetryable(504)).isTrue();

        // 4xx is the backend's considered answer; retrying it elsewhere cannot help and a
        // retried 429 makes the rate limit worse. 500 usually means application code ran,
        // so it may have had side effects.
        assertThat(policy.isStatusRetryable(400)).isFalse();
        assertThat(policy.isStatusRetryable(401)).isFalse();
        assertThat(policy.isStatusRetryable(404)).isFalse();
        assertThat(policy.isStatusRetryable(429)).isFalse();
        assertThat(policy.isStatusRetryable(500)).isFalse();
        assertThat(policy.isStatusRetryable(200)).isFalse();
    }

    @Test
    @DisplayName("retries transport failures that mean the request never landed")
    void retriesTransportFailures() {
        RetryPolicy policy = defaultPolicy();

        assertThat(policy.isFailureRetryable(new ConnectException("Connection refused"))).isTrue();
        assertThat(policy.isFailureRetryable(new ConnectTimeoutException("connect timed out"))).isTrue();
        assertThat(policy.isFailureRetryable(ReadTimeoutException.INSTANCE)).isTrue();
        assertThat(policy.isFailureRetryable(new IOException("Connection reset by peer"))).isTrue();
    }

    @Test
    @DisplayName("does not retry failures with no safe interpretation")
    void doesNotRetryUnknownFailures() {
        assertThat(defaultPolicy().isFailureRetryable(new IllegalStateException("bug"))).isFalse();
    }

    @Test
    @DisplayName("stops retrying once max-attempts is reached")
    void respectsMaxAttempts() {
        RetryPolicy policy = policy(true, 2, Set.of("GET"));

        assertThat(policy.canRetry("GET", 1, true)).isTrue();
        assertThat(policy.canRetry("GET", 2, true)).isFalse();
    }

    @Test
    @DisplayName("allows more attempts when configured to")
    void supportsMoreAttempts() {
        RetryPolicy policy = policy(true, 4, Set.of("GET"));

        assertThat(policy.canRetry("GET", 3, true)).isTrue();
        assertThat(policy.canRetry("GET", 4, false)).isFalse();
    }

    @Test
    @DisplayName("never retries a request whose body cannot be replayed")
    void requiresReplayableBody() {
        RetryPolicy policy = policy(true, 3, Set.of("GET", "POST"));

        assertThat(policy.canRetry("POST", 1, true)).isTrue();
        // A streamed body has already been consumed by the failed attempt; there is nothing
        // left to send.
        assertThat(policy.canRetry("POST", 1, false)).isFalse();
    }

    @Test
    @DisplayName("disabling retries disables every path")
    void disabledPolicyNeverRetries() {
        RetryPolicy policy = policy(false, 5, Set.of("GET"));

        assertThat(policy.isEnabled()).isFalse();
        assertThat(policy.maxAttempts()).isEqualTo(1);
        assertThat(policy.isMethodRetryable("GET")).isFalse();
        assertThat(policy.isStatusRetryable(503)).isFalse();
        assertThat(policy.isFailureRetryable(new ConnectException("refused"))).isFalse();
        assertThat(policy.canRetry("GET", 1, true)).isFalse();
    }
}
