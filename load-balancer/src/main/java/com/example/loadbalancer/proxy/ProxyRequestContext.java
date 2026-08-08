package com.example.loadbalancer.proxy;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.routing.BackendSelectionService;
import com.example.loadbalancer.routing.LoadBalancingContext;
import org.springframework.web.server.ServerWebExchange;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Per-request state carried across attempts.
 *
 * <p>Created once per client request and threaded through selection, forwarding, retries and
 * logging, so that the access log can report the complete story of a request — which
 * backends were tried, how many attempts it took, which route matched — rather than only the
 * last thing that happened.
 *
 * <h2>Thread confinement</h2>
 * Attempts are strictly sequential: attempt N+1 is only subscribed after attempt N has
 * terminated. A single request is therefore never mutating this object from two threads at
 * once. It may, however, <em>move</em> between event-loop threads between attempts, so
 * mutable fields are {@code volatile} to guarantee visibility across that hand-off. This is
 * deliberately not a thread-safe object shared between requests — one instance belongs to
 * exactly one request.
 */
public final class ProxyRequestContext {

    private final ServerWebExchange exchange;
    private final String requestId;
    private final String method;
    private final String rawPath;
    private final String rawQuery;
    private final String clientIp;
    private final String peerAddress;
    private final boolean trustedPeer;
    private final long startNanos;

    private final Set<String> triedBackendIds = new LinkedHashSet<>();

    private volatile ProxyRequestBody body = ProxyRequestBody.empty();
    private volatile int attempts;
    private volatile String routeId = BackendSelectionService.DEFAULT_ROUTE;
    private volatile String algorithm = "none";
    private volatile BackendServer currentBackend;
    private volatile int backendStatus = -1;

    public ProxyRequestContext(ServerWebExchange exchange,
                               String requestId,
                               String method,
                               String rawPath,
                               String rawQuery,
                               String clientIp,
                               String peerAddress,
                               boolean trustedPeer,
                               long startNanos) {
        this.exchange = exchange;
        this.requestId = requestId;
        this.method = method;
        this.rawPath = rawPath;
        this.rawQuery = rawQuery;
        this.clientIp = clientIp;
        this.peerAddress = peerAddress;
        this.trustedPeer = trustedPeer;
        this.startNanos = startNanos;
    }

    public ServerWebExchange exchange() {
        return exchange;
    }

    public String requestId() {
        return requestId;
    }

    public String method() {
        return method;
    }

    /** @return the path exactly as received, still percent-encoded. */
    public String rawPath() {
        return rawPath;
    }

    /** @return the query string exactly as received, or null. */
    public String rawQuery() {
        return rawQuery;
    }

    /** @return the resolved client IP, used for routing decisions and logging. */
    public String clientIp() {
        return clientIp;
    }

    /**
     * @return the raw TCP peer address. Distinct from {@link #clientIp()}: this is the hop the
     *         ALB actually observed and is what gets appended to {@code X-Forwarded-For}.
     */
    public String peerAddress() {
        return peerAddress;
    }

    /** @return true if the immediate peer is a configured trusted proxy. */
    public boolean trustedPeer() {
        return trustedPeer;
    }

    public long startNanos() {
        return startNanos;
    }

    public long elapsedNanos() {
        return System.nanoTime() - startNanos;
    }

    public ProxyRequestBody body() {
        return body;
    }

    public void body(ProxyRequestBody body) {
        this.body = body;
    }

    public int attempts() {
        return attempts;
    }

    /** @return the number of retries performed, i.e. attempts beyond the first. */
    public int retryCount() {
        return Math.max(0, attempts - 1);
    }

    public void beginAttempt() {
        attempts++;
    }

    public String routeId() {
        return routeId;
    }

    public void routeId(String routeId) {
        this.routeId = routeId;
    }

    public String algorithm() {
        return algorithm;
    }

    public void algorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public BackendServer currentBackend() {
        return currentBackend;
    }

    public void currentBackend(BackendServer backend) {
        this.currentBackend = backend;
    }

    /** @return the status the backend returned on the most recent attempt, or -1. */
    public int backendStatus() {
        return backendStatus;
    }

    public void backendStatus(int status) {
        this.backendStatus = status;
    }

    /** Marks a backend as tried so the next attempt selects a different one. */
    public void markTried(String backendId) {
        triedBackendIds.add(backendId);
    }

    public Set<String> triedBackendIds() {
        return Collections.unmodifiableSet(triedBackendIds);
    }

    /** @return the routing context for the next attempt, excluding backends already tried. */
    public LoadBalancingContext toLoadBalancingContext() {
        return new LoadBalancingContext(
                requestId, method, decodedPath(), clientIp, clientIp,
                attempts + 1, Set.copyOf(triedBackendIds), 0L);
    }

    /**
     * @return the path used for route matching. Route patterns are written against decoded
     *         paths, so matching must use the decoded form even though forwarding uses the
     *         raw one.
     */
    private String decodedPath() {
        return exchange.getRequest().getPath().value();
    }
}
