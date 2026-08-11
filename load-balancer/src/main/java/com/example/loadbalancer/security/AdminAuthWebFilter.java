package com.example.loadbalancer.security;

import com.example.loadbalancer.config.LoadBalancerProperties;
import com.example.loadbalancer.model.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Bearer-token authentication for the admin API.
 *
 * <h2>Why this endpoint needs protecting more than most</h2>
 * {@code /admin/backends} is not a read-only status page. An unauthenticated caller could
 * register a backend pointing at a host they control and receive every request the ALB
 * proxies — including {@code Authorization} headers and request bodies. Or disable every real
 * backend and cause a total outage with one curl. The admin API is effectively root on the
 * traffic path, and it must never be reachable without a credential.
 *
 * <h2>Design choices</h2>
 * <ul>
 *   <li><b>Constant-time comparison.</b> {@link MessageDigest#isEqual} instead of
 *       {@code String.equals}, which returns as soon as two bytes differ and thereby leaks the
 *       length of the matching prefix. That is enough to recover a token byte by byte over
 *       enough requests.</li>
 *   <li><b>Fail closed.</b> If the admin API is enabled with no token configured, the filter
 *       rejects everything rather than allowing everything. A missing environment variable is a
 *       deployment mistake; the safe interpretation is "locked", not "open".</li>
 *   <li><b>No token in this file, ever.</b> The value comes from configuration, which sources
 *       it from an environment variable. A default in code would ship in every image.</li>
 *   <li><b>Runs before the proxy filter.</b> Otherwise the catch-all proxy would swallow
 *       {@code /admin} paths before authentication had a chance to run.</li>
 * </ul>
 *
 * <p>This is a deliberately small, dependency-free implementation of one thing. For a
 * deployment that needs mTLS, OIDC, per-operator identity or an audit trail tied to a user,
 * replace it with Spring Security — the filter boundary is the same, so nothing else changes.
 */
@Component
public class AdminAuthWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthWebFilter.class);

    /** Before {@link com.example.loadbalancer.proxy.ProxyWebFilter#ORDER}. */
    public static final int ORDER = -100;

    private static final String BEARER_PREFIX = "Bearer ";

    private final ObjectMapper objectMapper;
    private final String pathPrefix;
    private final boolean enabled;
    private final byte[] expectedToken;

    public AdminAuthWebFilter(LoadBalancerProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.pathPrefix = normalise(properties.admin().pathPrefix());
        this.enabled = properties.admin().enabled();
        String token = properties.admin().token();
        this.expectedToken = token == null || token.isBlank()
                ? null
                : token.getBytes(StandardCharsets.UTF_8);

        if (!enabled) {
            log.info("Admin API is disabled; {}/** will return 404", pathPrefix);
        } else if (expectedToken == null) {
            log.error("Admin API is ENABLED but no token is configured. Every request to {}/** will be "
                    + "rejected with 503. Set load-balancer.admin.token (e.g. from ALB_ADMIN_TOKEN) "
                    + "or disable the admin API.", pathPrefix);
        } else if (expectedToken.length < 16) {
            log.warn("Configured admin token is only {} characters. Use at least 32 random characters; "
                    + "a short token is brute-forceable over the network.", expectedToken.length);
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!isAdminPath(path)) {
            return chain.filter(exchange);
        }

        if (!enabled) {
            // 404 rather than 403: a disabled admin API should not even confirm it exists.
            return reject(exchange, HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found");
        }
        if (expectedToken == null) {
            return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, "ADMIN_NOT_CONFIGURED",
                    "Admin API is not configured with an authentication token");
        }

        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (!isAuthorised(authorization)) {
            log.warn("Rejected unauthenticated admin request: {} {}",
                    exchange.getRequest().getMethod(), path);
            return reject(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "A valid bearer token is required");
        }
        return chain.filter(exchange);
    }

    private boolean isAuthorised(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return false;
        }
        byte[] presented = authorizationHeader.substring(BEARER_PREFIX.length())
                .trim()
                .getBytes(StandardCharsets.UTF_8);
        // isEqual is length-safe and constant-time with respect to content.
        return MessageDigest.isEqual(expectedToken, presented);
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"load-balancer-admin\"");
        }
        ErrorResponse body = new ErrorResponse(status.value(), code, message, null, Instant.now());
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception ex) {
            bytes = ("{\"status\":" + status.value() + ",\"error\":\"" + code + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private boolean isAdminPath(String path) {
        return path.equals(pathPrefix) || path.startsWith(pathPrefix + "/");
    }

    private static String normalise(String prefix) {
        String value = prefix == null || prefix.isBlank() ? "/admin" : prefix.trim();
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
