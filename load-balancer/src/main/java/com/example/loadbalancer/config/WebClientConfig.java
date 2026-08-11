package com.example.loadbalancer.config;

import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * Backend HTTP client and connection pool wiring.
 *
 * <h2>Pooling architecture</h2>
 * <pre>
 *   HttpClient (one instance, shared)
 *        │
 *        └── ConnectionProvider "alb-backends"
 *                 ├── pool for cde:8080   (max-connections idle+active)
 *                 ├── pool for cdf:8081
 *                 └── pool for cdg:8082
 * </pre>
 * Reactor Netty keys its pools by remote socket address, so a single provider already
 * gives <em>per-backend</em> pools with per-backend limits. That is the behaviour we want:
 * one saturated backend consumes its own 500 connections and cannot starve the others of
 * theirs. Creating a separate {@code HttpClient} per backend would add no isolation and
 * would multiply event-loop registrations for nothing.
 *
 * <h2>Why pooling matters here specifically</h2>
 * A new TCP connection per request costs a three-way handshake — and a TLS handshake for
 * HTTPS backends — before a single byte of the request is sent. At any real request rate
 * that dominates the ALB's own latency contribution, and it leaves thousands of sockets in
 * {@code TIME_WAIT}, which eventually exhausts the ephemeral port range and produces
 * "cannot assign requested address" failures that look like a backend outage.
 *
 * <h2>Separate pool for health checks</h2>
 * Health probes use their own provider. If they shared the traffic pool, a backend that
 * saturated its pool would also starve its own health checks, so the ALB would mark it DOWN
 * for being <em>busy</em> — and a busy backend that is dropped from the pool pushes its load
 * onto its peers, saturating them in turn. Isolating the probes keeps "is it alive" separate
 * from "is it loaded", which are different questions with different correct responses.
 */
@Configuration(proxyBeanMethods = false)
public class WebClientConfig {

    private static final Logger log = LoggerFactory.getLogger(WebClientConfig.class);

    /** Small dedicated pool for health probes; a handful of connections is ample. */
    private static final int HEALTH_CHECK_POOL_SIZE = 16;

    /**
     * The connection pool used for all proxied traffic.
     *
     * <p>{@code maxIdleTime} must be shorter than the backend's own keep-alive timeout,
     * otherwise the ALB will hand out a connection the backend is simultaneously closing and
     * the request fails with a premature close — the single most common "random 502s" cause
     * in reverse-proxy deployments. The 30s default is below the common 60s server default.
     * {@code maxLifeTime} additionally forces periodic reconnection so that DNS changes and
     * rolling backend replacements are eventually picked up.
     */
    @Bean(destroyMethod = "dispose")
    public ConnectionProvider backendConnectionProvider(LoadBalancerProperties properties) {
        LoadBalancerProperties.ConnectionPool pool = properties.connectionPool();
        log.info("Backend connection pool: maxConnections={} per backend, pendingAcquireTimeout={}, "
                        + "maxIdleTime={}, maxLifeTime={}, pendingAcquireMaxCount={}",
                pool.maxConnections(), pool.pendingAcquireTimeout(), pool.maxIdleTime(),
                pool.maxLifeTime(), properties.limits().maxPendingRequests());

        return ConnectionProvider.builder("alb-backends")
                .maxConnections(pool.maxConnections())
                .pendingAcquireTimeout(pool.pendingAcquireTimeout())
                .pendingAcquireMaxCount(properties.limits().maxPendingRequests())
                .maxIdleTime(pool.maxIdleTime())
                .maxLifeTime(pool.maxLifeTime())
                .evictInBackground(pool.evictInBackground())
                // LIFO: reuse the most recently used connection so idle ones age out and are
                // evicted, instead of round-robining every connection and keeping them all warm.
                .lifo()
                .metrics(true)
                .build();
    }

    /**
     * The shared client for proxied traffic.
     *
     * <p>{@code responseTimeout} here is the default ceiling on time-to-response; the
     * forwarder can still narrow it per request. Redirects are deliberately <em>not</em>
     * followed: a proxy must relay a 302 to the client, not resolve it — resolving it would
     * both hide the redirect from the caller and let a backend make the ALB issue requests to
     * arbitrary URLs, which is an SSRF primitive.
     */
    @Bean
    public HttpClient backendHttpClient(ConnectionProvider backendConnectionProvider,
                                        LoadBalancerProperties properties) {
        LoadBalancerProperties.Timeouts timeouts = properties.timeouts();
        return HttpClient.create(backendConnectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) timeouts.connection().toMillis())
                .option(ChannelOption.TCP_NODELAY, true)
                .responseTimeout(timeouts.response())
                .followRedirect(false)
                .compress(false)
                .metrics(true, uri -> "proxy")
                .keepAlive(true);
    }

    /** Isolated pool for active health checks. See the class javadoc for why. */
    @Bean(destroyMethod = "dispose")
    public ConnectionProvider healthCheckConnectionProvider(LoadBalancerProperties properties) {
        return ConnectionProvider.builder("alb-health-checks")
                .maxConnections(HEALTH_CHECK_POOL_SIZE)
                .pendingAcquireTimeout(properties.healthCheck().connectTimeout())
                .maxIdleTime(properties.healthCheck().interval().multipliedBy(2))
                .build();
    }

    /** Client used only by the health checker, with the health-check timeout budget. */
    @Bean
    public HttpClient healthCheckHttpClient(ConnectionProvider healthCheckConnectionProvider,
                                            LoadBalancerProperties properties) {
        LoadBalancerProperties.HealthCheck healthCheck = properties.healthCheck();
        return HttpClient.create(healthCheckConnectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) healthCheck.connectTimeout().toMillis())
                .responseTimeout(healthCheck.responseTimeout())
                .followRedirect(false)
                .compress(false)
                .keepAlive(true);
    }
}
