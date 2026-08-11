package com.example.loadbalancer.routing;

import com.example.loadbalancer.config.LoadBalancerProperties;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the currently active algorithm and swaps it atomically at runtime.
 *
 * <h2>Why an {@code AtomicReference} is sufficient</h2>
 * Switching algorithms is a single reference write. A request reads the current strategy
 * once, at selection time, and uses that reference for the rest of its life. So a switch
 * that happens mid-request cannot corrupt anything: the request either read the old
 * strategy (and completes against it) or the new one. There is no window in which a request
 * sees a half-installed algorithm, and no reader ever blocks — which is why this needs no
 * lock, no read-write lock and no volatile-plus-synchronized dance.
 *
 * <p>Strategy objects themselves are stateless with respect to a request — their state is
 * an internal cursor or cache — so retaining the old reference for the duration of an
 * in-flight request is safe and requires no reference counting or drain.
 *
 * <p>The current algorithm is intentionally <em>not</em> written back into
 * {@link LoadBalancerProperties}: the configuration object records what the operator
 * deployed, this class records what is running. Keeping them separate is what makes a
 * config reload able to detect and report drift, and it means the bound properties record
 * can stay immutable.
 */
@Component
public class AlgorithmManager {

    private static final Logger log = LoggerFactory.getLogger(AlgorithmManager.class);

    private final LoadBalancingStrategyFactory factory;
    private final AtomicReference<LoadBalancingAlgorithm> current;

    public AlgorithmManager(LoadBalancerProperties properties, LoadBalancingStrategyFactory factory) {
        this.factory = factory;
        LoadBalancingAlgorithm configured = properties.algorithm();
        // Fail fast at startup rather than on the first request.
        factory.getStrategy(configured);
        this.current = new AtomicReference<>(configured);
        log.info("Active load balancing algorithm: {}", configured);
    }

    /** @return the algorithm new requests will use. */
    public LoadBalancingAlgorithm current() {
        return current.get();
    }

    /** @return the strategy new requests will use. */
    public LoadBalancingStrategy currentStrategy() {
        return factory.getStrategy(current.get());
    }

    /** @return the strategy for {@code algorithm}, used by per-route algorithm overrides. */
    public LoadBalancingStrategy strategyFor(LoadBalancingAlgorithm algorithm) {
        return factory.getStrategy(algorithm);
    }

    /**
     * Switches the active algorithm.
     *
     * @param algorithm the new algorithm
     * @return the algorithm that was active before this call
     * @throws IllegalArgumentException if the algorithm has no registered strategy; validated
     *                                  <em>before</em> the swap so a bad request cannot leave
     *                                  the ALB pointing at a missing strategy
     */
    public LoadBalancingAlgorithm switchTo(LoadBalancingAlgorithm algorithm) {
        factory.getStrategy(algorithm);
        LoadBalancingAlgorithm previous = current.getAndSet(algorithm);
        if (previous != algorithm) {
            log.info("Load balancing algorithm switched: {} -> {}", previous, algorithm);
        }
        return previous;
    }

    /** Parses and switches in one step; used by the admin API. */
    public LoadBalancingAlgorithm switchTo(String algorithmName) {
        return switchTo(LoadBalancingAlgorithm.parse(algorithmName));
    }
}
