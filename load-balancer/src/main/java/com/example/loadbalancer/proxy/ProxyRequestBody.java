package com.example.loadbalancer.proxy;

import com.example.loadbalancer.exception.RequestTooLargeException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The request body, in one of two modes: streamed once, or buffered and replayable.
 *
 * <h2>The tension this resolves</h2>
 * A proxy should stream bodies — holding a 10MB upload in heap multiplies memory by the
 * number of concurrent uploads and is how a reverse proxy gets OOM-killed by ordinary
 * traffic. But a retry has to <em>re-send</em> the body, and a stream can only be consumed
 * once. You cannot have both properties for the same request.
 *
 * <p>So the choice is made explicitly, per request, before anything is consumed:
 * <ul>
 *   <li><b>No body</b> (the common case for the retryable methods GET/HEAD/OPTIONS) —
 *       replayable for free. This is why the defaults give you retries and streaming at the
 *       same time with no memory cost at all.</li>
 *   <li><b>Body, buffering enabled, length known and within the cap</b> — read into a byte
 *       array once and replay it per attempt.</li>
 *   <li><b>Anything else</b> — stream straight through, and the request is not retryable.
 *       Correctness beats availability here: silently buffering a large upload to make a
 *       retry possible would trade a rare failed request for a predictable memory blowup.</li>
 * </ul>
 *
 * <p>Buffering is only attempted when {@code Content-Length} is known and within the cap.
 * Speculatively buffering an unknown-length body would mean discovering it is too large
 * <em>after</em> partially consuming it, at which point neither streaming nor buffering is
 * possible any more.
 */
public final class ProxyRequestBody {

    private static final DataBufferFactory BUFFER_FACTORY = new DefaultDataBufferFactory();

    private final byte[] buffered;
    private final Flux<DataBuffer> stream;
    private final boolean empty;

    private ProxyRequestBody(byte[] buffered, Flux<DataBuffer> stream, boolean empty) {
        this.buffered = buffered;
        this.stream = stream;
        this.empty = empty;
    }

    /** A request with no body: replayable, costs nothing. */
    public static ProxyRequestBody empty() {
        return new ProxyRequestBody(null, null, true);
    }

    /** A body held in memory, replayable across retries. */
    public static ProxyRequestBody buffered(byte[] bytes) {
        return new ProxyRequestBody(bytes, null, bytes.length == 0);
    }

    /** A body streamed straight through; consumable exactly once. */
    public static ProxyRequestBody streaming(Flux<DataBuffer> stream) {
        return new ProxyRequestBody(null, stream, false);
    }

    /**
     * @return true if this body can be sent again. Only replayable bodies may be retried —
     *         the proxy service checks this before scheduling another attempt.
     */
    public boolean isReplayable() {
        return empty || buffered != null;
    }

    public boolean isEmpty() {
        return empty;
    }

    /** @return known length in bytes, or -1 when the body is streamed with unknown length. */
    public long length() {
        if (empty) {
            return 0;
        }
        return buffered != null ? buffered.length : -1;
    }

    /**
     * @return the body content for one attempt. Buffered bodies produce a fresh
     *         {@link DataBuffer} per call, so two attempts never share a buffer whose read
     *         position the first attempt already advanced.
     */
    public Flux<DataBuffer> content() {
        if (empty) {
            return Flux.empty();
        }
        if (buffered != null) {
            return Flux.defer(() -> Flux.just(BUFFER_FACTORY.wrap(buffered)));
        }
        return stream;
    }

    /**
     * Captures the body of an inbound request according to the size limits and retry policy.
     *
     * @param request        inbound request
     * @param wantReplayable whether the retry policy would like a replayable body
     * @param maxBufferBytes largest body worth buffering for a retry
     * @param maxBodyBytes   hard limit; larger bodies are rejected with 413
     */
    public static Mono<ProxyRequestBody> capture(ServerHttpRequest request,
                                                 boolean wantReplayable,
                                                 long maxBufferBytes,
                                                 long maxBodyBytes) {
        long contentLength = request.getHeaders().getContentLength();

        // Reject oversized bodies from the declared length, before reading a single byte.
        if (contentLength > maxBodyBytes) {
            return Mono.error(new RequestTooLargeException(maxBodyBytes));
        }

        boolean definitelyEmpty = contentLength == 0
                || (contentLength < 0 && !hasBodyFraming(request.getHeaders()));
        if (definitelyEmpty) {
            return Mono.just(empty());
        }

        if (wantReplayable && contentLength >= 0 && contentLength <= maxBufferBytes) {
            return DataBufferUtils.join(request.getBody(), (int) maxBufferBytes)
                    .map(dataBuffer -> {
                        try {
                            byte[] bytes = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(bytes);
                            return buffered(bytes);
                        } finally {
                            // join() hands over ownership of the joined buffer; releasing it
                            // here is what stops a pooled direct buffer from leaking.
                            DataBufferUtils.release(dataBuffer);
                        }
                    })
                    .defaultIfEmpty(empty());
        }

        return Mono.just(streaming(limited(request.getBody(), maxBodyBytes)));
    }

    /**
     * Enforces the body limit on a stream of unknown length by counting bytes as they pass.
     *
     * <p>The {@code doOnDiscard} hook matters: when the limit trips, Reactor discards
     * buffers that were already in flight, and a discarded {@link DataBuffer} that is never
     * released is a native memory leak on Netty's pooled allocator. It leaks only on the
     * error path, which is exactly the path that hostile traffic takes.
     */
    private static Flux<DataBuffer> limited(Flux<DataBuffer> body, long maxBodyBytes) {
        AtomicLong counter = new AtomicLong();
        return body
                .map(dataBuffer -> {
                    if (counter.addAndGet(dataBuffer.readableByteCount()) > maxBodyBytes) {
                        DataBufferUtils.release(dataBuffer);
                        throw new RequestTooLargeException(maxBodyBytes);
                    }
                    return dataBuffer;
                })
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
    }

    /** @return true if the headers declare a body of some kind. */
    private static boolean hasBodyFraming(HttpHeaders headers) {
        return headers.containsKey(HttpHeaders.TRANSFER_ENCODING)
                || headers.getContentLength() > 0;
    }
}
