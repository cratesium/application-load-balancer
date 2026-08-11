package com.example.loadbalancer.config;

import com.example.loadbalancer.backend.BackendRegistry;
import com.example.loadbalancer.backend.BackendSpec;
import com.example.loadbalancer.model.AdminDtos;
import com.example.loadbalancer.routing.AlgorithmManager;
import com.example.loadbalancer.routing.RouteRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Re-reads configuration and applies the parts that can be changed at runtime.
 *
 * <h2>What reloads, and what does not</h2>
 * <table border="1">
 *   <caption>Reload support</caption>
 *   <tr><th>Setting</th><th>Reloadable</th><th>Why</th></tr>
 *   <tr><td>algorithm</td><td>yes</td><td>one atomic reference swap</td></tr>
 *   <tr><td>backend list, weights</td><td>yes</td><td>registry reconciles, preserving live counters</td></tr>
 *   <tr><td>routes</td><td>yes</td><td>compiled then swapped atomically</td></tr>
 *   <tr><td>health-check thresholds</td><td>no</td><td>baked into per-backend counters at creation</td></tr>
 *   <tr><td>timeouts, pool sizing</td><td>no</td><td>fixed when the Netty client was built</td></tr>
 *   <tr><td>limits, admin token</td><td>no</td><td>read once at startup</td></tr>
 * </table>
 *
 * <p>The unsupported items are <em>reported</em> in the response rather than being silently
 * ignored: an operator who edits a timeout, reloads, and is told "OK" would reasonably believe
 * the new timeout is live. Naming exactly what did not apply is the difference between a useful
 * partial reload and a misleading one.
 *
 * <p>Rebuilding the Netty client and pools live is possible but not worth it: it means draining
 * and replacing every pooled connection, and a restart under a rolling deploy achieves the same
 * result with clearer semantics. The runtime-adjustable knobs are the ones needed during an
 * incident — traffic distribution and pool membership — and those all work.
 *
 * <h2>Reconciliation, not replacement</h2>
 * Backends are diffed by id and address. A backend present in both old and new configuration
 * keeps its existing object, so its active-connection count, health-check hysteresis and circuit
 * breaker survive the reload. Replacing every backend object on reload would reset all of that
 * and briefly make the whole pool look unhealthy — a reload should be a no-op when nothing
 * changed.
 */
@Component
public class ConfigurationReloader {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationReloader.class);

    private static final List<String> NON_RELOADABLE = List.of(
            "timeouts.*", "connection-pool.*", "limits.*", "admin.*",
            "health-check.interval", "health-check.failure-threshold", "health-check.success-threshold",
            "circuit-breaker.*", "retry.*", "listen.*");

    private final ConfigurableEnvironment environment;
    private final BackendRegistry backendRegistry;
    private final RouteRegistry routeRegistry;
    private final AlgorithmManager algorithmManager;

    public ConfigurationReloader(ConfigurableEnvironment environment,
                                 BackendRegistry backendRegistry,
                                 RouteRegistry routeRegistry,
                                 AlgorithmManager algorithmManager) {
        this.environment = environment;
        this.backendRegistry = backendRegistry;
        this.routeRegistry = routeRegistry;
        this.algorithmManager = algorithmManager;
    }

    /**
     * Rebinds {@code load-balancer.*} from the current environment and applies the reloadable
     * parts.
     *
     * <p>Binding and validation happen before anything is applied, so a malformed
     * configuration fails the reload and leaves the running configuration untouched. A reload
     * that half-applies is worse than one that refuses.
     */
    public AdminDtos.ReloadResponse reload() {
        LoadBalancerProperties reloaded;
        try {
            reloaded = Binder.get(environment)
                    .bind("load-balancer", LoadBalancerProperties.class)
                    .orElseThrow(() -> new IllegalStateException(
                            "No load-balancer configuration found in the environment"));
            ConfigurationValidator.validate(reloaded);
        } catch (RuntimeException ex) {
            log.error("Configuration reload rejected; the running configuration is unchanged", ex);
            return new AdminDtos.ReloadResponse(false, algorithmManager.current().name(),
                    List.of(), List.of(), NON_RELOADABLE,
                    "Reload rejected: " + ex.getMessage());
        }

        List<BackendSpec> desired = reloaded.backends().stream().map(BackendSpec::from).toList();
        BackendRegistry.ReconcileResult result = backendRegistry.reconcile(desired);
        routeRegistry.replaceAll(reloaded.routes());
        algorithmManager.switchTo(reloaded.algorithm());

        log.info("Configuration reloaded: algorithm={} added={} removed={}",
                reloaded.algorithm(), result.added(), result.removed());

        return new AdminDtos.ReloadResponse(true, reloaded.algorithm().name(),
                result.added(), result.removed(), NON_RELOADABLE,
                "Reloaded. Settings listed in 'unsupported' require a restart to take effect.");
    }
}
