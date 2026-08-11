package com.example.loadbalancer.controller;

import com.example.loadbalancer.backend.BackendRegistry;
import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.backend.BackendSpec;
import com.example.loadbalancer.config.ConfigurationReloader;
import com.example.loadbalancer.lifecycle.DrainingCoordinator;
import com.example.loadbalancer.metrics.LoadBalancerMetrics;
import com.example.loadbalancer.model.AdminDtos;
import com.example.loadbalancer.model.BackendView;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import com.example.loadbalancer.resilience.CircuitBreaker;
import com.example.loadbalancer.resilience.CircuitBreakerRegistry;
import com.example.loadbalancer.routing.AlgorithmManager;
import com.example.loadbalancer.routing.BackendSelectionService;
import com.example.loadbalancer.routing.RouteRegistry;
import com.example.loadbalancer.lifecycle.ReadinessManager;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The control-plane HTTP API.
 *
 * <p>Authentication is enforced by {@code AdminAuthWebFilter} before any of these methods run,
 * so the controller contains no auth logic — a separation that makes it possible to test the
 * two independently.
 *
 * <h2>Two rules this controller follows throughout</h2>
 * <ol>
 *   <li><b>Nothing internal is serialised.</b> Every response is a DTO. Returning
 *       {@code BackendServer} directly would expose its atomics to Jackson (producing odd
 *       shapes) and, worse, would leak a mutable handle on live routing state into whatever
 *       else touched the response.</li>
 *   <li><b>Removal drains first.</b> {@code DELETE} and {@code disable} do not sever in-flight
 *       requests; they hand the backend to the draining coordinator. An admin API that drops
 *       live requests makes rolling deploys user-visible.</li>
 * </ol>
 *
 * <p>All operations are thread-safe by virtue of delegating to the registries, which are built
 * for concurrent access. The controller itself holds no mutable state.
 */
@RestController
@RequestMapping("${load-balancer.admin.path-prefix:/admin}")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final BackendRegistry backendRegistry;
    private final AlgorithmManager algorithmManager;
    private final BackendSelectionService selectionService;
    private final CircuitBreakerRegistry circuitBreakers;
    private final DrainingCoordinator drainingCoordinator;
    private final RouteRegistry routeRegistry;
    private final LoadBalancerMetrics metrics;
    private final ReadinessManager readinessManager;
    private final ConfigurationReloader configurationReloader;
    private final Instant startedAt = Instant.now();

    public AdminController(BackendRegistry backendRegistry,
                          AlgorithmManager algorithmManager,
                          BackendSelectionService selectionService,
                          CircuitBreakerRegistry circuitBreakers,
                          DrainingCoordinator drainingCoordinator,
                          RouteRegistry routeRegistry,
                          LoadBalancerMetrics metrics,
                          ReadinessManager readinessManager,
                          ConfigurationReloader configurationReloader) {
        this.backendRegistry = backendRegistry;
        this.algorithmManager = algorithmManager;
        this.selectionService = selectionService;
        this.circuitBreakers = circuitBreakers;
        this.drainingCoordinator = drainingCoordinator;
        this.routeRegistry = routeRegistry;
        this.metrics = metrics;
        this.readinessManager = readinessManager;
        this.configurationReloader = configurationReloader;
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    /** {@code GET /admin/status} — one-glance view of what the ALB is doing. */
    @GetMapping("/status")
    public Mono<AdminDtos.StatusView> status() {
        return Mono.fromSupplier(() -> new AdminDtos.StatusView(
                algorithmManager.current().name(),
                backendRegistry.all().size(),
                selectionService.healthyCount(),
                metrics.activeRequests(),
                metrics.totalRequests(),
                metrics.failedRequests(),
                metrics.noHealthyBackendRejections(),
                circuitBreakers.openCircuitCount(),
                readinessManager.isAcceptingTraffic(),
                startedAt,
                Duration.between(startedAt, Instant.now()).toSeconds()));
    }

    // ------------------------------------------------------------------
    // Backends
    // ------------------------------------------------------------------

    /** {@code GET /admin/backends} */
    @GetMapping("/backends")
    public Mono<List<BackendView>> listBackends() {
        return Mono.fromSupplier(() -> backendRegistry.all().stream()
                .map(backend -> BackendView.from(backend, circuitBreakers.stateOf(backend.id())))
                .toList());
    }

    /** {@code GET /admin/backends/{id}} */
    @GetMapping("/backends/{id}")
    public Mono<ResponseEntity<BackendView>> getBackend(@PathVariable String id) {
        return Mono.fromSupplier(() -> backendRegistry.find(id)
                .map(backend -> ResponseEntity.ok(BackendView.from(backend, circuitBreakers.stateOf(id))))
                .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    /**
     * {@code POST /admin/backends} — registers a new backend.
     *
     * <p>Returns 409 on a duplicate id rather than overwriting: an id is an identity, and
     * silently replacing one would orphan the in-flight requests counted against it.
     */
    @PostMapping("/backends")
    public Mono<ResponseEntity<?>> addBackend(@Valid @RequestBody AdminDtos.AddBackendRequest request) {
        return Mono.fromSupplier(() -> {
            BackendSpec spec = new BackendSpec(request.id(), request.host(), request.port(),
                    request.secureOrDefault(), request.weightOrDefault(), request.enabledOrDefault());
            try {
                BackendServer created = backendRegistry.register(spec);
                log.info("Admin API registered backend id={} url={}", created.id(), created.baseUrl());
                return ResponseEntity.status(HttpStatus.CREATED)
                        .body(BackendView.from(created, circuitBreakers.stateOf(created.id())));
            } catch (IllegalStateException duplicate) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new AdminDtos.AckResponse(request.id(), "CONFLICT", duplicate.getMessage()));
            } catch (IllegalArgumentException invalid) {
                return ResponseEntity.badRequest()
                        .body(new AdminDtos.AckResponse(request.id(), "INVALID", invalid.getMessage()));
            }
        });
    }

    /**
     * {@code DELETE /admin/backends/{id}} — drains, then removes.
     *
     * <p>Responds 202 Accepted, not 204: removal completes asynchronously once in-flight
     * requests finish or the drain timeout expires. Reporting 204 would claim the backend was
     * already gone while it was still serving requests.
     */
    @DeleteMapping("/backends/{id}")
    public Mono<ResponseEntity<AdminDtos.AckResponse>> removeBackend(@PathVariable String id) {
        return Mono.fromSupplier(() -> backendRegistry.find(id)
                .map(backend -> {
                    backendRegistry.disable(id);
                    drainingCoordinator.drainAndRemove(backend);
                    return ResponseEntity.accepted().body(new AdminDtos.AckResponse(id, "DRAINING",
                            "Backend is draining " + backend.activeConnections()
                                    + " in-flight request(s) and will then be removed"));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new AdminDtos.AckResponse(id, "NOT_FOUND", "No such backend"))));
    }

    /** {@code POST /admin/backends/{id}/disable} — drains, then parks in DISABLED. */
    @PostMapping("/backends/{id}/disable")
    public Mono<ResponseEntity<AdminDtos.AckResponse>> disableBackend(@PathVariable String id) {
        return Mono.fromSupplier(() -> backendRegistry.find(id)
                .map(backend -> {
                    backendRegistry.disable(id);
                    drainingCoordinator.drain(backend);
                    return ResponseEntity.accepted().body(new AdminDtos.AckResponse(id, "DRAINING",
                            "Backend is no longer receiving new requests; "
                                    + backend.activeConnections() + " in-flight request(s) will complete"));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new AdminDtos.AckResponse(id, "NOT_FOUND", "No such backend"))));
    }

    /**
     * {@code POST /admin/backends/{id}/enable} — returns a backend to service.
     *
     * <p>With active health checks on, the backend enters {@code DOWN} and must pass
     * {@code success-threshold} probes before receiving traffic.
     */
    @PostMapping("/backends/{id}/enable")
    public Mono<ResponseEntity<AdminDtos.AckResponse>> enableBackend(@PathVariable String id) {
        return Mono.fromSupplier(() -> {
            if (!backendRegistry.enable(id)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new AdminDtos.AckResponse(id, "NOT_FOUND", "No such backend"));
            }
            BackendServer backend = backendRegistry.find(id).orElseThrow();
            return ResponseEntity.ok(new AdminDtos.AckResponse(id, backend.state().name(),
                    "Backend enabled; it will receive traffic once it is UP"));
        });
    }

    /** {@code PUT /admin/backends/{id}/weight} — takes effect on the next selection. */
    @PutMapping("/backends/{id}/weight")
    public Mono<ResponseEntity<?>> updateWeight(@PathVariable String id,
                                                @Valid @RequestBody AdminDtos.WeightUpdateRequest request) {
        return Mono.fromSupplier(() -> {
            if (!backendRegistry.updateWeight(id, request.weight())) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new AdminDtos.AckResponse(id, "NOT_FOUND", "No such backend"));
            }
            BackendServer backend = backendRegistry.find(id).orElseThrow();
            return ResponseEntity.ok(BackendView.from(backend, circuitBreakers.stateOf(id)));
        });
    }

    /** {@code POST /admin/backends/{id}/circuit-breaker/reset} — forces a breaker closed. */
    @PostMapping("/backends/{id}/circuit-breaker/reset")
    public Mono<ResponseEntity<AdminDtos.AckResponse>> resetCircuitBreaker(@PathVariable String id) {
        return Mono.fromSupplier(() -> {
            if (backendRegistry.find(id).isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new AdminDtos.AckResponse(id, "NOT_FOUND", "No such backend"));
            }
            CircuitBreaker breaker = circuitBreakers.forBackend(id);
            breaker.reset();
            return ResponseEntity.ok(new AdminDtos.AckResponse(id, breaker.state().name(),
                    "Circuit breaker reset"));
        });
    }

    // ------------------------------------------------------------------
    // Algorithm
    // ------------------------------------------------------------------

    /** {@code GET /admin/algorithm} */
    @GetMapping("/algorithm")
    public Mono<AdminDtos.AlgorithmView> getAlgorithm() {
        return Mono.fromSupplier(() -> new AdminDtos.AlgorithmView(
                algorithmManager.current().name(),
                List.of(LoadBalancingAlgorithm.values()).stream().map(Enum::name).toList()));
    }

    /**
     * {@code POST /admin/load-balancer/algorithm} — hot-swaps the algorithm.
     *
     * <p>The change is a single atomic reference write. In-flight requests continue with the
     * strategy they already selected; the next request uses the new one. No restart, no drain,
     * no window in which routing is undefined.
     */
    @PostMapping("/load-balancer/algorithm")
    public Mono<ResponseEntity<?>> switchAlgorithm(
            @Valid @RequestBody AdminDtos.AlgorithmChangeRequest request) {
        return Mono.fromSupplier(() -> {
            try {
                LoadBalancingAlgorithm previous = algorithmManager.switchTo(request.algorithm());
                return ResponseEntity.ok(new AdminDtos.AlgorithmChangeResponse(
                        previous.name(), algorithmManager.current().name()));
            } catch (IllegalArgumentException invalid) {
                return ResponseEntity.badRequest().body(new AdminDtos.AckResponse(
                        request.algorithm(), "INVALID_ALGORITHM", invalid.getMessage()));
            }
        });
    }

    /** Convenience alias so operators do not have to remember two path shapes. */
    @PostMapping("/algorithm")
    public Mono<ResponseEntity<?>> switchAlgorithmAlias(
            @Valid @RequestBody AdminDtos.AlgorithmChangeRequest request) {
        return switchAlgorithm(request);
    }

    // ------------------------------------------------------------------
    // Routes and configuration
    // ------------------------------------------------------------------

    /** {@code GET /admin/routes} */
    @GetMapping("/routes")
    public Mono<List<AdminDtos.RouteView>> listRoutes() {
        return Mono.fromSupplier(() -> routeRegistry.all().stream()
                .map(rule -> new AdminDtos.RouteView(
                        rule.id(), rule.pathSpec(), rule.methods(), rule.backendIds(),
                        rule.algorithm() == null ? null : rule.algorithm().name()))
                .toList());
    }

    /** {@code POST /admin/config/reload} — re-reads configuration without a restart. */
    @PostMapping("/config/reload")
    public Mono<ResponseEntity<AdminDtos.ReloadResponse>> reloadConfig() {
        return Mono.fromSupplier(() -> {
            AdminDtos.ReloadResponse response = configurationReloader.reload();
            return response.reloaded()
                    ? ResponseEntity.ok(response)
                    : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        });
    }
}
