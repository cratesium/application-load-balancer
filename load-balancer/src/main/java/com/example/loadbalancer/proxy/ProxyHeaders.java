package com.example.loadbalancer.proxy;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.config.LoadBalancerProperties;
import com.example.loadbalancer.exception.InvalidRequestException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedCaseInsensitiveMap;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the header set sent to the backend and the header set returned to the client.
 *
 * <h2>Hop-by-hop headers</h2>
 * RFC 9110 divides headers into end-to-end (belonging to the message) and hop-by-hop
 * (belonging to a single TCP connection). Forwarding a hop-by-hop header is not a style
 * issue — it is a correctness bug. Copying {@code Transfer-Encoding: chunked} onto a request
 * whose body the ALB is re-framing produces a message whose declared framing contradicts its
 * actual framing, which is the raw material of request smuggling. Copying {@code Connection:
 * close} propagates a decision about <em>our</em> socket onto the backend's. Copying
 * {@code Upgrade} offers a protocol switch this proxy cannot actually perform. All of them
 * are stripped in both directions, along with any header the {@code Connection} header names
 * — that is the mechanism by which a peer declares additional hop-by-hop headers.
 *
 * <h2>Forwarding headers and spoofing</h2>
 * Inbound {@code X-Forwarded-*} headers are only preserved when the peer is a configured
 * trusted proxy. From an untrusted peer they are discarded and replaced with what the ALB
 * actually observed. Without this, a client can claim any origin IP, protocol or host, and
 * every downstream decision based on those values — rate limits, audit trails, HTTPS
 * redirect logic — becomes attacker-controlled.
 *
 * <h2>Tracing headers</h2>
 * {@code traceparent}, {@code tracestate} and {@code baggage} are ordinary end-to-end
 * headers and are forwarded untouched, so a trace that starts at the client survives the hop
 * through the ALB with no tracing dependency present.
 */
@Component
public class ProxyHeaders {

    /**
     * Headers that belong to one connection and must never be forwarded.
     * {@code Host} is handled separately because it is rewritten rather than dropped.
     */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "proxy-connection",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");

    private static final Set<String> FORWARDING_HEADERS = Set.of(
            "x-forwarded-for",
            "x-forwarded-proto",
            "x-forwarded-host",
            "x-forwarded-port",
            "forwarded");

    public static final String X_FORWARDED_FOR = "X-Forwarded-For";
    public static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";
    public static final String X_FORWARDED_HOST = "X-Forwarded-Host";
    public static final String X_FORWARDED_PORT = "X-Forwarded-Port";

    private final boolean preserveHost;
    private final boolean addForwardedHeaders;
    private final String requestIdHeader;

    public ProxyHeaders(LoadBalancerProperties properties) {
        this.preserveHost = properties.proxy().preserveHostHeader();
        this.addForwardedHeaders = properties.proxy().addForwardedHeaders();
        this.requestIdHeader = properties.proxy().requestIdHeader();
    }

    /**
     * Builds the headers for the outbound backend request.
     *
     * @param request     the inbound client request
     * @param backend     the selected backend, used for the rewritten {@code Host}
     * @param requestId   correlation id, always propagated
     * @param peerAddress the TCP peer address — the address <em>this</em> hop observed, which is
     *                    what gets appended to {@code X-Forwarded-For}. Deliberately not the
     *                    resolved client IP: appending that would duplicate an entry already in
     *                    the chain and misrepresent the hop list
     * @param trustedPeer whether the immediate peer is a configured trusted proxy
     */
    public HttpHeaders buildRequestHeaders(ServerHttpRequest request,
                                           BackendServer backend,
                                           String requestId,
                                           String peerAddress,
                                           boolean trustedPeer) {
        HttpHeaders inbound = request.getHeaders();
        Set<String> connectionTokens = connectionTokens(inbound);
        HttpHeaders outbound = new HttpHeaders();

        for (Map.Entry<String, List<String>> entry : inbound.entrySet()) {
            String name = entry.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower) || connectionTokens.contains(lower)) {
                continue;
            }
            if (lower.equals("host")) {
                continue; // set below, according to preserve-host-header
            }
            if (lower.equals("content-length")) {
                continue; // re-derived from the body we actually send
            }
            if (FORWARDING_HEADERS.contains(lower) && !trustedPeer) {
                continue; // untrusted peer: discard its claims, we set our own below
            }
            outbound.put(name, List.copyOf(entry.getValue()));
        }

        if (addForwardedHeaders) {
            applyForwardedHeaders(outbound, request, peerAddress, trustedPeer);
        }
        outbound.set(requestIdHeader, requestId);

        if (preserveHost) {
            String originalHost = inbound.getFirst(HttpHeaders.HOST);
            if (originalHost != null) {
                outbound.set(HttpHeaders.HOST, originalHost);
            }
        } else {
            // Default: the backend sees its own authority, so its absolute-URI generation,
            // virtual hosting and redirects are self-consistent.
            outbound.set(HttpHeaders.HOST, backend.authority());
        }
        return outbound;
    }

    private void applyForwardedHeaders(HttpHeaders outbound,
                                       ServerHttpRequest request,
                                       String peerAddress,
                                       boolean trustedPeer) {
        if (peerAddress != null) {
            if (trustedPeer) {
                // Append the address we received the connection from, extending the chain that
                // the trusted proxies before us built: "client, proxy1" becomes
                // "client, proxy1, thisPeer". Appending the *resolved* client instead would
                // duplicate the leftmost entry and describe a hop list that never happened.
                String existing = request.getHeaders().getFirst(X_FORWARDED_FOR);
                outbound.set(X_FORWARDED_FOR, existing == null || existing.isBlank()
                        ? peerAddress
                        : existing + ", " + peerAddress);
            } else {
                // Untrusted peer: discard whatever it claimed and state only what we observed.
                outbound.set(X_FORWARDED_FOR, peerAddress);
            }
        }

        String scheme = request.getURI().getScheme();
        if (scheme != null) {
            outbound.set(X_FORWARDED_PROTO, scheme);
        }
        String host = request.getHeaders().getFirst(HttpHeaders.HOST);
        if (host != null && !host.isBlank()) {
            outbound.set(X_FORWARDED_HOST, host);
        }
        int port = request.getURI().getPort();
        if (port > 0) {
            outbound.set(X_FORWARDED_PORT, String.valueOf(port));
        }
    }

    /**
     * Copies backend response headers to the client, stripping hop-by-hop headers.
     *
     * <p>{@code Content-Length} is preserved when present: the body is streamed through
     * byte-for-byte, so the declared length remains accurate.
     */
    public void copyResponseHeaders(HttpHeaders backendHeaders, HttpHeaders clientHeaders) {
        Set<String> connectionTokens = connectionTokens(backendHeaders);
        for (Map.Entry<String, List<String>> entry : backendHeaders.entrySet()) {
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower) || connectionTokens.contains(lower)) {
                continue;
            }
            clientHeaders.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
    }

    /**
     * Rejects requests whose framing is ambiguous.
     *
     * <p>A message carrying both {@code Content-Length} and {@code Transfer-Encoding}, or two
     * disagreeing {@code Content-Length} values, is the classic request-smuggling setup: the
     * proxy and the backend disagree about where the request ends, so bytes the proxy treats
     * as a body are parsed by the backend as the start of a second, attacker-authored
     * request. Netty's decoder catches most malformed framing, but the ALB refusing it
     * explicitly means the guarantee does not depend on a server-internal detail.
     *
     * @throws InvalidRequestException if the request framing is ambiguous
     */
    public void validateRequestFraming(ServerHttpRequest request) {
        HttpHeaders headers = request.getHeaders();
        List<String> contentLengths = headers.get(HttpHeaders.CONTENT_LENGTH);
        boolean hasTransferEncoding = headers.containsKey(HttpHeaders.TRANSFER_ENCODING);

        if (contentLengths != null && !contentLengths.isEmpty()) {
            if (hasTransferEncoding) {
                throw new InvalidRequestException(
                        "Request specifies both Content-Length and Transfer-Encoding");
            }
            if (contentLengths.size() > 1 || contentLengths.get(0).contains(",")) {
                throw new InvalidRequestException("Request specifies multiple Content-Length values");
            }
        }
        for (String name : headers.keySet()) {
            if (containsControlCharacters(name)) {
                throw new InvalidRequestException("Request contains an illegal header name");
            }
            List<String> values = headers.get(name);
            if (values == null) {
                continue;
            }
            for (String value : values) {
                if (containsControlCharacters(value)) {
                    throw new InvalidRequestException("Header '" + name + "' contains illegal characters");
                }
            }
        }
    }

    /**
     * CR, LF or NUL in a header would let a caller terminate the header block early and
     * inject headers — or an entire second response — into the message we build.
     */
    private static boolean containsControlCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == 0) {
                return true;
            }
        }
        return false;
    }

    /** @return lowercase header names listed in {@code Connection}, which are hop-by-hop. */
    private static Set<String> connectionTokens(HttpHeaders headers) {
        List<String> connection = headers.get(HttpHeaders.CONNECTION);
        if (connection == null || connection.isEmpty()) {
            return Set.of();
        }
        Map<String, Boolean> tokens = new LinkedCaseInsensitiveMap<>();
        for (String value : connection) {
            for (String token : value.split(",")) {
                String trimmed = token.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty()) {
                    tokens.put(trimmed, Boolean.TRUE);
                }
            }
        }
        return Set.copyOf(tokens.keySet());
    }

    public String requestIdHeader() {
        return requestIdHeader;
    }
}
