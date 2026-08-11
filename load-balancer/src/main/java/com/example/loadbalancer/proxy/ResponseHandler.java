package com.example.loadbalancer.proxy;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Relays a backend response to the client.
 *
 * <h2>Streaming, not buffering</h2>
 * The body is piped as a {@link Flux} of {@link DataBuffer}s straight from the backend
 * connection to the client connection. It is never materialised into a {@code byte[]} or a
 * {@code String}, so a 500MB download costs a few kilobytes of ALB heap and works
 * identically to a 500-byte one. Server-sent events and other long-lived streams work for
 * the same reason: bytes are forwarded as they arrive rather than at completion.
 *
 * <p>The client's own consumption rate propagates backwards through the reactive chain: a
 * slow client stops requesting buffers, so the ALB stops reading from the backend socket, so
 * TCP flow control eventually slows the backend. That is what stops a slow reader from
 * making the proxy accumulate the whole response in memory.
 */
@Component
public class ResponseHandler {

    private final ProxyHeaders proxyHeaders;

    public ResponseHandler(ProxyHeaders proxyHeaders) {
        this.proxyHeaders = proxyHeaders;
    }

    /**
     * Writes status, headers and body of {@code backendResponse} to the client.
     *
     * <p>Once this method has been called the response is committed and the request can no
     * longer be retried — which is why the retry decision is made before it, not inside it.
     *
     * @return a Mono completing when the last byte has been written to the client
     */
    public Mono<Void> relay(ProxyRequestContext context, ClientResponse backendResponse) {
        ServerHttpResponse response = context.exchange().getResponse();
        response.setStatusCode(backendResponse.statusCode());
        proxyHeaders.copyResponseHeaders(backendResponse.headers().asHttpHeaders(), response.getHeaders());
        response.getHeaders().set(proxyHeaders.requestIdHeader(), context.requestId());

        Flux<DataBuffer> body = backendResponse.bodyToFlux(DataBuffer.class);
        return response.writeWith(body)
                // If the client vanishes mid-response, buffers already read from the backend
                // are discarded by the reactive chain; without this they are never returned
                // to Netty's pooled allocator.
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
    }
}
