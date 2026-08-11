package com.example.loadbalancer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Request and response payloads for the admin API.
 *
 * <p>Grouped in one file because they are small, purely structural, and only meaningful
 * together. Requests are validated with Bean Validation so a bad admin call fails with a
 * 400 and a field-level message instead of a stack trace.
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /** {@code POST /admin/backends} */
    public record AddBackendRequest(
            @NotBlank String id,
            @NotBlank String host,
            @Min(1) @Max(65535) int port,
            Boolean secure,
            @Positive Integer weight,
            Boolean enabled) {

        public boolean secureOrDefault() {
            return secure != null && secure;
        }

        public int weightOrDefault() {
            return weight == null ? 1 : weight;
        }

        public boolean enabledOrDefault() {
            return enabled == null || enabled;
        }
    }

    /** {@code PUT /admin/backends/{id}/weight} */
    public record WeightUpdateRequest(@Positive int weight) {
    }

    /** {@code POST /admin/load-balancer/algorithm} */
    public record AlgorithmChangeRequest(@NotBlank String algorithm) {
    }

    /** Response to an algorithm change; echoes both sides so the caller can confirm the swap. */
    public record AlgorithmChangeResponse(String previousAlgorithm, String currentAlgorithm) {
    }

    /** {@code GET /admin/algorithm} */
    public record AlgorithmView(String algorithm, List<String> supported) {
    }

    /** {@code GET /admin/status} */
    public record StatusView(
            String algorithm,
            int totalBackends,
            int healthyBackends,
            int activeRequests,
            long totalRequests,
            long failedRequests,
            long noHealthyBackendRejections,
            int openCircuits,
            boolean acceptingTraffic,
            Instant startedAt,
            long uptimeSeconds) {
    }

    /** {@code GET /admin/routes} */
    public record RouteView(String id, String path, Set<String> methods, List<String> backends, String algorithm) {
    }

    /** {@code POST /admin/config/reload} */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReloadResponse(
            boolean reloaded,
            String algorithm,
            List<String> addedBackends,
            List<String> removedBackends,
            List<String> unsupported,
            String message) {
    }

    /** Generic acknowledgement for state-changing admin calls. */
    public record AckResponse(String id, String status, String message) {
    }
}
