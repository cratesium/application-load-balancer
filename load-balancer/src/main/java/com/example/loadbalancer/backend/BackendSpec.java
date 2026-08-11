package com.example.loadbalancer.backend;

import com.example.loadbalancer.config.LoadBalancerProperties;

/**
 * The immutable description needed to create a backend.
 *
 * <p>Exists so that {@link BackendRegistry} does not depend on the shape of the
 * configuration file: a backend can be born from {@code application.yml}, from the admin
 * API, or later from a service-discovery provider, and all three funnel through this one
 * type.
 */
public record BackendSpec(String id, String host, int port, boolean secure, int weight, boolean enabled) {

    public BackendSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Backend id must not be blank");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Backend '" + id + "' host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Backend '" + id + "' port must be in 1..65535 but was " + port);
        }
        if (weight < 1) {
            throw new IllegalArgumentException("Backend '" + id + "' weight must be >= 1 but was " + weight);
        }
        id = id.trim();
        host = host.trim();
    }

    public static BackendSpec from(LoadBalancerProperties.Backend backend) {
        return new BackendSpec(backend.id(), backend.host(), backend.port(),
                backend.secure(), backend.weight(), backend.enabled());
    }
}
