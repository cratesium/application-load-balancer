package com.example.loadbalancer.proxy;

import com.example.loadbalancer.config.LoadBalancerProperties;
import com.example.loadbalancer.exception.OverloadedException;
import com.example.loadbalancer.lifecycle.ReadinessManager;
import com.example.loadbalancer.metrics.LoadBalancerMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The data-plane entry point: intercepts every request that is not a management call and
 * hands it to the proxy.
 *
 * <h2>Why a WebFilter instead of a controller</h2>
 * A {@code @RequestMapping("/**")} controller would work for the happy path and then fight
 * the framework for everything else. A filter gives direct access to
 * {@link ServerWebExchange}, and with it:
 * <ul>
 *   <li><b>The raw body.</b> {@code Flux<DataBuffer>} straight off the socket, with no codec
 *       deciding to materialise it. A controller parameter implies a decoder, and a decoder
 *       implies buffering.</li>
 *   <li><b>The raw target.</b> Handler mapping normalises and decodes paths before a
 *       controller sees them, so {@code %2F} and duplicate slashes would be silently rewritten
 *       — changing what the backend receives.</li>
 *   <li><b>Any method.</b> Including ones Spring has no annotation for, which a real proxy
 *       must pass through rather than answer with 405.</li>
 *   <li><b>No handler-mapping cost</b> on the hottest path in the process.</li>
 * </ul>
 *
 * <h2>Ordering</h2>
 * Runs late enough that admin authentication has already happened, and it terminates the chain
 * for proxied requests — {@code chain.filter} is only called for management paths, which is
 * what lets the actuator and admin controllers coexist with a catch-all proxy.
 */
@Component
public class ProxyWebFilter implements WebFilter, Ordered {

    /** After the admin auth filter, before Spring's handler mapping. */
    public static final int ORDER = -50;

    private final ProxyService proxyService;
    private final ConcurrencyLimiter concurrencyLimiter;
    private final ReadinessManager readinessManager;
    private final LoadBalancerMetrics metrics;
    private final String adminPrefix;
    private final String actuatorPrefix;

    public ProxyWebFilter(ProxyService proxyService,
                          ConcurrencyLimiter concurrencyLimiter,
                          ReadinessManager readinessManager,
                          LoadBalancerMetrics metrics,
                          LoadBalancerProperties properties,
                          @Value("${management.endpoints.web.base-path:/actuator}") String actuatorPrefix) {
        this.proxyService = proxyService;
        this.concurrencyLimiter = concurrencyLimiter;
        this.readinessManager = readinessManager;
        this.metrics = metrics;
        this.adminPrefix = normalise(properties.admin().pathPrefix());
        this.actuatorPrefix = normalise(actuatorPrefix);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (isManagementPath(path)) {
            return chain.filter(exchange);
        }

        // Refuse new work once shutdown has begun. In-flight requests are unaffected; this
        // only stops the ALB from accepting work it may not have time to finish.
        if (!readinessManager.isAcceptingTraffic()) {
            return Mono.error(new OverloadedException(concurrencyLimiter.limit(), concurrencyLimiter.inFlight()));
        }

        if (!concurrencyLimiter.tryAcquire()) {
            metrics.recordOverload();
            return Mono.error(new OverloadedException(concurrencyLimiter.limit(), concurrencyLimiter.inFlight()));
        }

        // doFinally rather than doOnTerminate: a client that disconnects mid-response cancels
        // the chain, and a cancelled request that never released its slot would permanently
        // shrink the concurrency budget.
        return proxyService.proxy(exchange)
                .doFinally(signal -> concurrencyLimiter.release());
    }

    /**
     * @return true for the ALB's own control-plane endpoints, which are served locally rather
     *         than proxied. Note that {@code /health} is <em>not</em> in this set: it is a
     *         perfectly ordinary application path that must be forwarded to backends. The
     *         ALB's own health lives under the actuator prefix.
     */
    private boolean isManagementPath(String path) {
        return isUnder(path, adminPrefix) || isUnder(path, actuatorPrefix);
    }

    private static boolean isUnder(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static String normalise(String prefix) {
        String value = prefix == null || prefix.isBlank() ? "/" : prefix.trim();
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
