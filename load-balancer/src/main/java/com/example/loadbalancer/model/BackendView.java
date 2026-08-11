package com.example.loadbalancer.model;

import com.example.loadbalancer.backend.BackendServer;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Read-only projection of a {@link BackendServer} for the admin API.
 *
 * <p>The live backend object owns atomic counters that the data plane mutates on every
 * request; serialising it directly would both expose internals and produce a torn view.
 * This record is a consistent-enough copy taken at one instant.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BackendView(
        String id,
        String host,
        int port,
        boolean secure,
        String url,
        int weight,
        String status,
        int activeConnections,
        long totalRequests,
        long successfulRequests,
        long failedRequests,
        String circuitState,
        Instant lastHealthCheck,
        Instant lastFailure,
        Instant lastStateChange,
        String lastFailureReason) {

    public static BackendView from(BackendServer backend, String circuitState) {
        return new BackendView(
                backend.id(),
                backend.host(),
                backend.port(),
                backend.secure(),
                backend.baseUrl(),
                backend.weight(),
                backend.state().name(),
                backend.activeConnections(),
                backend.totalRequests(),
                backend.successfulRequests(),
                backend.failedRequests(),
                circuitState,
                backend.lastHealthCheck(),
                backend.lastFailure(),
                backend.lastStateChange(),
                backend.lastFailureReason());
    }
}
