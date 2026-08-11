package com.example.loadbalancer.proxy;

import com.example.loadbalancer.backend.BackendChangeEvent;
import com.example.loadbalancer.backend.BackendRegistry;
import com.example.loadbalancer.backend.BackendServer;
import jakarta.annotation.PostConstruct;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Supplies a {@link WebClient} per backend, all sharing one connection provider.
 *
 * <p>The clients are thin: they differ only in base URL. The expensive, stateful part — the
 * TCP connection pool — lives in the shared {@link HttpClient}'s provider and is keyed by
 * remote address, so each backend still gets its own isolated pool without each backend
 * needing its own client.
 *
 * <p>Clients are cached because constructing one per request would defeat pooling entirely
 * for any per-client state, and they are evicted when a backend is unregistered so that a
 * long-lived ALB in an autoscaled environment does not accumulate one client per instance it
 * has ever seen.
 */
@Component
public class BackendClientFactory {

    private final HttpClient httpClient;
    private final BackendRegistry backendRegistry;
    private final Map<String, WebClient> clients = new ConcurrentHashMap<>();

    public BackendClientFactory(HttpClient backendHttpClient, BackendRegistry backendRegistry) {
        this.httpClient = backendHttpClient;
        this.backendRegistry = backendRegistry;
    }

    @PostConstruct
    void subscribeToBackendChanges() {
        backendRegistry.addListener(this::onBackendChange);
    }

    private void onBackendChange(BackendChangeEvent event) {
        if (event.type() == BackendChangeEvent.Type.REMOVED) {
            clients.remove(cacheKey(event.backend()));
        }
    }

    /** @return the client for a backend, created on first use. */
    public WebClient forBackend(BackendServer backend) {
        return clients.computeIfAbsent(cacheKey(backend), key -> WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(backend.baseUrl())
                .build());
    }

    /**
     * Keyed by id <em>and</em> address so that a backend re-registered under the same id at a
     * different address cannot be served by a client still pointing at the old one.
     */
    private static String cacheKey(BackendServer backend) {
        return backend.id() + "@" + backend.baseUrl();
    }

    int cachedClientCount() {
        return clients.size();
    }
}
