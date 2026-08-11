package com.example.loadbalancer.exception;

import org.springframework.http.HttpStatus;

/**
 * A backend did not respond inside its timeout budget.
 *
 * <p>Kept separate from {@link BackendUnavailableException} because the operational
 * response is different: a refused connection means the process is gone, whereas a
 * timeout usually means it is alive and overloaded. Conflating them into one 502 hides
 * that distinction from dashboards.
 */
public final class BackendTimeoutException extends LoadBalancerException {

    private final String backendId;
    private final String timeoutKind;

    public BackendTimeoutException(String backendId, String timeoutKind, String message, Throwable cause) {
        super(HttpStatus.GATEWAY_TIMEOUT, "GATEWAY_TIMEOUT", message, cause);
        this.backendId = backendId;
        this.timeoutKind = timeoutKind;
    }

    public String backendId() {
        return backendId;
    }

    /** @return which budget expired: {@code CONNECT}, {@code RESPONSE} or {@code REQUEST}. */
    public String timeoutKind() {
        return timeoutKind;
    }
}
