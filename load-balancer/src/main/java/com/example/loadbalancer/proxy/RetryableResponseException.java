package com.example.loadbalancer.proxy;

/**
 * Internal signal that a backend returned a status worth retrying elsewhere.
 *
 * <p>Not part of the public {@code LoadBalancerException} hierarchy: it never reaches a
 * client. It exists purely to unwind the reactive chain from inside the response handler
 * back to the retry loop, carrying the status so that the loop can report the real backend
 * status if no alternative backend turns out to be available.
 *
 * <p>The response body has already been released by the time this is thrown — a retryable
 * response is discarded, not relayed.
 */
final class RetryableResponseException extends RuntimeException {

    private final int status;
    private final String backendId;

    RetryableResponseException(String backendId, int status) {
        super("Backend " + backendId + " returned retryable status " + status, null, false, false);
        this.backendId = backendId;
        this.status = status;
    }

    int status() {
        return status;
    }

    String backendId() {
        return backendId;
    }
}
