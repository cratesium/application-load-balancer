package com.example.loadbalancer.proxy;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.exception.InvalidRequestException;
import com.example.loadbalancer.retry.RetryPolicy;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Issues one attempt against one backend.
 *
 * <p>Knows nothing about retries, selection or metrics — it takes a backend and a request and
 * produces a response, which is what makes it independently testable against a stub server.
 *
 * <h2>SSRF: where the target URL comes from</h2>
 * The scheme, host and port of the outbound request come <em>only</em> from
 * {@link BackendServer#baseUrl()}, which comes only from the registry, which comes only from
 * configuration or an authenticated admin call. Nothing a client sends can influence the
 * authority — not a header, not a path, not a query parameter. This is the structural reason
 * the ALB cannot be turned into an SSRF gadget: there is no code path from request data to
 * target host. A {@code ?url=} style proxy would have one, which is why it does not exist here.
 *
 * <p>The client-supplied path is appended after the authority is fixed, so even a path like
 * {@code //evil.com/x} produces {@code http://backend:8080//evil.com/x} — a request to the
 * configured backend, not to {@code evil.com}. Redirects are not followed
 * (see {@code WebClientConfig}), so a backend cannot make the ALB fetch a URL either.
 */
@Component
public class RequestForwarder {

    private final BackendClientFactory clientFactory;
    private final ProxyHeaders proxyHeaders;
    private final ResponseHandler responseHandler;
    private final RetryPolicy retryPolicy;

    public RequestForwarder(BackendClientFactory clientFactory,
                            ProxyHeaders proxyHeaders,
                            ResponseHandler responseHandler,
                            RetryPolicy retryPolicy) {
        this.clientFactory = clientFactory;
        this.proxyHeaders = proxyHeaders;
        this.responseHandler = responseHandler;
        this.retryPolicy = retryPolicy;
    }

    /**
     * Forwards the request to {@code backend} and relays the response to the client.
     *
     * @param retryAllowed whether the caller is able and willing to make another attempt. When
     *                     false, a retryable status such as 503 is relayed to the client as-is
     *                     rather than being swallowed — the client gets the backend's real
     *                     answer instead of a synthesised error
     * @return a Mono completing when the response has been fully written to the client, or
     *         failing with {@link RetryableResponseException} to request another attempt
     */
    public Mono<Void> forward(BackendServer backend, ProxyRequestContext context, boolean retryAllowed) {
        URI target = buildTargetUri(backend, context);
        HttpMethod method = HttpMethod.valueOf(context.method());
        WebClient client = clientFactory.forBackend(backend);

        WebClient.RequestBodySpec spec = client
                .method(method)
                .uri(target)
                .headers(headers -> headers.addAll(proxyHeaders.buildRequestHeaders(
                        context.exchange().getRequest(), backend, context.requestId(),
                        context.peerAddress(), context.trustedPeer())));

        WebClient.RequestHeadersSpec<?> request = context.body().isEmpty()
                ? spec
                : spec.body(BodyInserters.fromDataBuffers(context.body().content()));

        return request.exchangeToMono(response -> handleResponse(backend, context, response, retryAllowed));
    }

    private Mono<Void> handleResponse(BackendServer backend,
                                      ProxyRequestContext context,
                                      ClientResponse response,
                                      boolean retryAllowed) {
        int status = response.statusCode().value();
        context.backendStatus(status);

        if (retryAllowed && retryPolicy.isStatusRetryable(status)) {
            // Discard this response and ask for another backend. releaseBody() is essential:
            // an unconsumed response body keeps its connection checked out of the pool
            // forever, so a few retried requests would silently exhaust the pool.
            return response.releaseBody()
                    .then(Mono.error(new RetryableResponseException(backend.id(), status)));
        }
        return responseHandler.relay(context, response);
    }

    /**
     * Builds the absolute target URI: backend authority from the registry, path and query
     * byte-for-byte from the client.
     *
     * <p>The <em>raw</em> path and query are used deliberately. Decoding and re-encoding would
     * change {@code %2F} into {@code /} and alter what the backend receives — a difference
     * that matters both for correctness (a path segment containing an encoded slash) and for
     * security (backends whose authorisation rules are written against the literal path).
     */
    private URI buildTargetUri(BackendServer backend, ProxyRequestContext context) {
        String path = context.rawPath();
        if (path == null || !path.startsWith("/")) {
            // Origin-form requests always begin with '/'. Anything else is an absolute-form
            // or authority-form request that must not be turned into a backend URL.
            throw new InvalidRequestException("Request target must be an absolute path");
        }
        String query = context.rawQuery();
        StringBuilder url = new StringBuilder(backend.baseUrl().length() + path.length() + 32)
                .append(backend.baseUrl())
                .append(path);
        if (query != null && !query.isEmpty()) {
            url.append('?').append(query);
        }
        try {
            // Constructed from an already-encoded string, so no further encoding is applied.
            return new URI(url.toString());
        } catch (URISyntaxException ex) {
            throw new InvalidRequestException("Request target is not a valid URI");
        }
    }
}
