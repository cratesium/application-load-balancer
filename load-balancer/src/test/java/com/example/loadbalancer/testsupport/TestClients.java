package com.example.loadbalancer.testsupport;

import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * Builds {@link WebTestClient}s for the integration tests.
 *
 * <h2>Why not just use the injected WebTestClient</h2>
 * The client Spring Boot injects for {@code RANDOM_PORT} shares Reactor Netty's <em>default global</em>
 * connection provider, whose {@code maxConnections} is sized from the CPU count (a couple of dozen).
 * That is shared across every test class in the JVM, and the tests here deliberately drive hundreds
 * of requests and dozens of concurrent threads. Running one class at a time stays under the limit;
 * running the whole suite does not, and the symptom is a request blocking on connection acquisition
 * until the read timeout — a hang that looks exactly like a bug in the proxy and is not one.
 *
 * <p>A dedicated, generously sized provider per client removes that coupling. It also isolates the
 * <em>test harness's</em> pool from the pool the ALB uses for its backends, so an assertion about
 * ALB pool behaviour cannot be perturbed by the client's own pool.
 */
public final class TestClients {

    /** Ample for the concurrency these tests generate; far above the shared default. */
    private static final int MAX_CONNECTIONS = 500;

    private TestClients() {
    }

    /**
     * @param port the ALB's port
     * @param name a label for the pool, so a leak shows up attributed in a thread dump
     * @return a client with its own connection pool and a generous response timeout
     */
    public static WebTestClient forPort(int port, String name) {
        ConnectionProvider provider = ConnectionProvider.builder(name)
                .maxConnections(MAX_CONNECTIONS)
                .pendingAcquireTimeout(Duration.ofSeconds(20))
                .build();

        return WebTestClient
                .bindToServer(new ReactorClientHttpConnector(HttpClient.create(provider)))
                .baseUrl("http://127.0.0.1:" + port)
                // Generous: some tests deliberately exercise 10s backend delays and drain timeouts.
                .responseTimeout(Duration.ofSeconds(30))
                // The default 256KB in-memory limit is exceeded by the Prometheus scrape once
                // latency histograms exist across several tag combinations. Worth knowing for real
                // deployments too: percentile histograms dominate the size of a scrape payload.
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    /** As {@link #forPort(int, String)}, with a bearer token attached to every request. */
    public static WebTestClient withToken(int port, String name, String token) {
        return forPort(port, name).mutate()
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
    }
}
