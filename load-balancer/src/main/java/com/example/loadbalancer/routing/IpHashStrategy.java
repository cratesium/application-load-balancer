package com.example.loadbalancer.routing;

import com.example.loadbalancer.backend.BackendServer;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import com.example.loadbalancer.routing.hash.Murmur3;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps a client to a backend by hashing its IP: {@code index = murmur3(ip) mod n}.
 *
 * <p>Gives session affinity without any shared session store — the same client keeps
 * reaching the same backend, so an in-process cache or session on that backend stays warm.
 *
 * <h2>Two properties worth being honest about</h2>
 * <ol>
 *   <li><b>Affinity is only as stable as {@code n}.</b> Modulo arithmetic means that
 *       adding or removing one backend remaps roughly <em>every</em> client, not just the
 *       ones on the affected server. If stable affinity across pool changes is the goal,
 *       {@link ConsistentHashStrategy} is the correct choice; this one is for a fixed pool.
 *       Note that a backend failing health checks changes {@code n} too, so a single
 *       flapping backend reshuffles the whole map each time it flaps.</li>
 *   <li><b>Distribution follows clients, not load.</b> A large NAT gateway or corporate
 *       proxy is one IP, so all of its users land on one backend. IP hashing balances
 *       source addresses, which is not the same as balancing work.</li>
 * </ol>
 *
 * <p>The IP itself is resolved upstream by {@code ClientIpResolver}, which only honours
 * {@code X-Forwarded-For} from configured trusted proxies. That matters here more than
 * anywhere else: if the header were trusted blindly, any client could pick its own backend
 * by setting one header, turning affinity into an attacker-controlled routing primitive
 * for overloading a single server.
 *
 * <p>The hash key is taken from {@link LoadBalancingContext#resolvedAffinityKey()} rather
 * than the IP field directly, so cookie- or header-based stickiness can be introduced later
 * by changing the resolver alone.
 */
@Component
public class IpHashStrategy implements LoadBalancingStrategy {

    @Override
    public BackendServer selectBackend(List<BackendServer> healthyBackends, LoadBalancingContext context) {
        int size = healthyBackends.size();
        if (size == 1) {
            return healthyBackends.get(0);
        }
        int hash = Murmur3.hash32(context.resolvedAffinityKey());
        return healthyBackends.get(Murmur3.toIndex(hash, size));
    }

    @Override
    public LoadBalancingAlgorithm algorithm() {
        return LoadBalancingAlgorithm.IP_HASH;
    }
}
