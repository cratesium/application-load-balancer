package com.example.loadbalancer.testsupport;

import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A real HTTP server on a real socket, used by the integration tests.
 *
 * <h2>Why this rather than Testcontainers</h2>
 * The requirements suggest Testcontainers "where useful". For these tests it is not: everything
 * being verified — routing, retries, header fidelity, timeouts — is HTTP behaviour, and none of it
 * needs process or filesystem isolation. An embedded Reactor Netty server starts in milliseconds
 * instead of seconds, needs no Docker daemon (so the suite runs in any CI container), and lets a
 * test assert on what the backend actually received. Docker Compose covers the containerised
 * topology, which is the thing Testcontainers would genuinely add.
 *
 * <p>Records every request it receives so tests can assert on forwarded headers, methods, paths,
 * query strings and bodies — the fidelity guarantees a proxy has to make.
 */
public final class StubBackend implements AutoCloseable {

    /** One received request, captured for assertions. */
    public record ReceivedRequest(String method, String uri, Map<String, String> headers, String body) {

        public String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    private final String name;
    private final DisposableServer server;
    private final List_ requests = new List_();
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final AtomicInteger failuresRemaining = new AtomicInteger();
    private final AtomicInteger failureStatus = new AtomicInteger(503);
    private final AtomicLong artificialDelayMillis = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxObservedInFlight = new AtomicInteger();

    /** Simple synchronised list wrapper, to keep the generic noise out of the field declaration. */
    private static final class List_ extends CopyOnWriteArrayList<ReceivedRequest> {
    }

    private StubBackend(String name, DisposableServer server) {
        this.name = name;
        this.server = server;
    }

    /** Starts a backend on an ephemeral port. */
    public static StubBackend start(String name) {
        Holder holder = new Holder();
        DisposableServer server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    StubBackend self = holder.backend;
                    Map<String, String> headers = new ConcurrentHashMap<>();
                    request.requestHeaders().forEach(entry ->
                            headers.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), entry.getValue()));

                    String uri = request.uri();

                    if (uri.startsWith("/health")) {
                        boolean up = self == null || self.healthy.get();
                        return response.status(up ? 200 : 503)
                                .header("Content-Type", "application/json")
                                .sendString(reactor.core.publisher.Mono.just(
                                        "{\"status\":\"" + (up ? "UP" : "DOWN") + "\"}"));
                    }

                    return request.receive().aggregate().asString(StandardCharsets.UTF_8)
                            .defaultIfEmpty("")
                            .flatMap(body -> {
                                if (self != null) {
                                    self.requests.add(new ReceivedRequest(
                                            request.method().name(), uri, headers, body));
                                    int current = self.inFlight.incrementAndGet();
                                    self.maxObservedInFlight.accumulateAndGet(current, Math::max);
                                }
                                int failStatus = -1;
                                if (self != null && self.failuresRemaining.getAndUpdate(
                                        remaining -> remaining > 0 ? remaining - 1 : 0) > 0) {
                                    failStatus = self.failureStatus.get();
                                }
                                long delay = self == null ? 0 : self.artificialDelayMillis.get();

                                String payload = "{\"server\":\"" + (self == null ? "?" : self.name)
                                        + "\",\"path\":\"" + uri + "\",\"body\":"
                                        + (body.isEmpty() ? "null" : "\"" + body.replace("\"", "\\\"") + "\"")
                                        + "}";

                                var send = response
                                        .status(failStatus > 0 ? failStatus : 200)
                                        .header("Content-Type", "application/json")
                                        .header("X-Served-By", self == null ? "?" : self.name)
                                        .sendString(reactor.core.publisher.Mono.just(payload))
                                        .then();

                                return (delay > 0
                                        ? send.delaySubscription(Duration.ofMillis(delay))
                                        : send)
                                        .doFinally(signal -> {
                                            if (self != null) {
                                                self.inFlight.decrementAndGet();
                                            }
                                        });
                            });
                })
                .bindNow();

        StubBackend backend = new StubBackend(name, server);
        holder.backend = backend;
        return backend;
    }

    /** Lets the request handler reach the instance it belongs to. */
    private static final class Holder {
        private volatile StubBackend backend;
    }

    public String name() {
        return name;
    }

    public int port() {
        return server.port();
    }

    public String host() {
        return "127.0.0.1";
    }

    /** Flips the {@code /health} response, so active health transitions can be driven. */
    public void healthy(boolean value) {
        healthy.set(value);
    }

    /** Makes the next {@code count} non-health requests fail with {@code status}. */
    public void failNextRequests(int count, int status) {
        failureStatus.set(status);
        failuresRemaining.set(count);
    }

    /** Delays every response, for timeout and least-connections tests. */
    public void responseDelay(Duration delay) {
        artificialDelayMillis.set(delay.toMillis());
    }

    public java.util.List<ReceivedRequest> receivedRequests() {
        return java.util.List.copyOf(requests);
    }

    public int requestCount() {
        return requests.size();
    }

    /** @return the highest number of simultaneous in-flight requests this backend saw. */
    public int maxObservedInFlight() {
        return maxObservedInFlight.get();
    }

    public void reset() {
        requests.clear();
        failuresRemaining.set(0);
        artificialDelayMillis.set(0);
        maxObservedInFlight.set(0);
        healthy.set(true);
    }

    @Override
    public void close() {
        server.disposeNow(Duration.ofSeconds(5));
    }
}
