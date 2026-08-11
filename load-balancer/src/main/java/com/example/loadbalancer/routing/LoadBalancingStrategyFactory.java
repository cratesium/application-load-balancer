package com.example.loadbalancer.routing;

import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a {@link LoadBalancingAlgorithm} to its implementation.
 *
 * <p>Strategies are discovered by injecting every {@link LoadBalancingStrategy} bean and
 * indexing them by the algorithm they report. Adding an algorithm therefore means adding an
 * enum constant and a {@code @Component} — no switch statement anywhere has to be found and
 * updated, and no registration list can drift out of sync with reality.
 *
 * <p>Startup fails if any algorithm has no implementation or if two implementations claim
 * the same one. Both are programming errors, and both would otherwise surface as a
 * {@code NullPointerException} on a production request after the operator switches
 * algorithm — the worst possible time to find out.
 */
@Component
public class LoadBalancingStrategyFactory {

    private static final Logger log = LoggerFactory.getLogger(LoadBalancingStrategyFactory.class);

    private final Map<LoadBalancingAlgorithm, LoadBalancingStrategy> strategies =
            new EnumMap<>(LoadBalancingAlgorithm.class);

    public LoadBalancingStrategyFactory(List<LoadBalancingStrategy> availableStrategies) {
        for (LoadBalancingStrategy strategy : availableStrategies) {
            LoadBalancingStrategy previous = strategies.put(strategy.algorithm(), strategy);
            if (previous != null) {
                throw new IllegalStateException("Two strategies claim algorithm " + strategy.algorithm()
                        + ": " + previous.getClass().getName() + " and " + strategy.getClass().getName());
            }
        }
        for (LoadBalancingAlgorithm algorithm : LoadBalancingAlgorithm.values()) {
            if (!strategies.containsKey(algorithm)) {
                throw new IllegalStateException("No LoadBalancingStrategy implementation registered for "
                        + algorithm + ". Every value of LoadBalancingAlgorithm must have exactly one.");
            }
        }
        log.info("Registered {} load balancing strategies: {}", strategies.size(), strategies.keySet());
    }

    /**
     * @param algorithm the algorithm to resolve
     * @return its strategy, never null
     * @throws IllegalArgumentException if {@code algorithm} is null
     */
    public LoadBalancingStrategy getStrategy(LoadBalancingAlgorithm algorithm) {
        if (algorithm == null) {
            throw new IllegalArgumentException(
                    "Algorithm must not be null. Supported values: " + LoadBalancingAlgorithm.supportedValues());
        }
        LoadBalancingStrategy strategy = strategies.get(algorithm);
        if (strategy == null) {
            // Unreachable given the constructor check; kept as a guard against a future
            // enum constant being added without an implementation.
            throw new IllegalArgumentException("Unsupported load balancing algorithm: " + algorithm);
        }
        return strategy;
    }

    /** @return every algorithm that can be selected at runtime. */
    public List<LoadBalancingAlgorithm> supported() {
        return List.copyOf(strategies.keySet());
    }
}
