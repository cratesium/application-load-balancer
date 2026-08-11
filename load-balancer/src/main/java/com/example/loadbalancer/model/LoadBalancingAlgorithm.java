package com.example.loadbalancer.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The set of supported backend-selection algorithms.
 *
 * <p>This enum is the single source of truth for algorithm identity. Configuration,
 * the admin API and the strategy factory all key off it, so adding an algorithm is a
 * two-step change: add a constant here and register a {@code LoadBalancingStrategy}
 * bean that reports it.
 */
public enum LoadBalancingAlgorithm {

    /** Even, stateless rotation across healthy backends. */
    ROUND_ROBIN,

    /** Rotation biased by backend weight, using a smooth (non-bursty) schedule. */
    WEIGHTED_ROUND_ROBIN,

    /** Uniform random choice. */
    RANDOM,

    /** Fewest in-flight requests wins. */
    LEAST_CONNECTIONS,

    /** Fewest in-flight requests per unit of capacity wins. */
    WEIGHTED_LEAST_CONNECTIONS,

    /** Deterministic mapping from client IP to backend (session affinity). */
    IP_HASH,

    /** Hash ring with virtual nodes; minimises reshuffling when the pool changes. */
    CONSISTENT_HASH;

    /**
     * Parses an algorithm name case-insensitively, failing fast with a message that
     * lists the valid values rather than a bare {@code IllegalArgumentException}.
     *
     * @param value the configured or requested algorithm name
     * @return the matching algorithm
     * @throws IllegalArgumentException if {@code value} is null, blank or unknown
     */
    public static LoadBalancingAlgorithm parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Load balancing algorithm must not be blank. Supported values: " + supportedValues());
        }
        String normalised = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return LoadBalancingAlgorithm.valueOf(normalised);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unsupported load balancing algorithm '" + value + "'. Supported values: " + supportedValues());
        }
    }

    /** @return a human-readable, comma-separated list of every supported algorithm. */
    public static String supportedValues() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
