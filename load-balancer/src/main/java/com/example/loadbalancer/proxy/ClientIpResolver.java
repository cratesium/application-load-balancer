package com.example.loadbalancer.proxy;

import com.example.loadbalancer.config.LoadBalancerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Determines the client IP, and refuses to be lied to about it.
 *
 * <h2>The threat</h2>
 * {@code X-Forwarded-For} is just a request header: any client can send
 * {@code X-Forwarded-For: 10.0.0.1}. If the ALB believes it unconditionally, the header
 * stops being information and becomes a control channel. Concretely, with
 * {@code IP_HASH} routing an attacker chooses which backend serves them — pick the value
 * that hashes to one server and send all their traffic there, and a "load balanced" system
 * concentrates an attack on a single node. The same header also drives rate limiting, audit
 * logs and geo rules in most deployments, so trusting it blindly corrupts all of them.
 *
 * <h2>The rule implemented here</h2>
 * The TCP peer address is the only address that cannot be forged. So:
 * <ul>
 *   <li>If the peer is <b>not</b> in {@code trusted-proxies}, the peer address <em>is</em>
 *       the client IP and any forwarding headers are ignored entirely.</li>
 *   <li>If the peer <b>is</b> a trusted proxy, walk {@code X-Forwarded-For} from right to
 *       left, skipping entries that are themselves trusted proxies, and take the first
 *       untrusted address. Right-to-left matters: the left-hand entries are the ones an
 *       attacker can pre-populate, the right-hand ones were appended by infrastructure you
 *       control.</li>
 * </ul>
 *
 * <p>The default configuration trusts nothing, which is the only safe default: an ALB
 * deployed straight onto the internet must not honour these headers, and an operator who
 * puts a CDN in front makes a deliberate decision to list it.
 */
@Component
public class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final List<CidrRange> trustedProxies;

    public ClientIpResolver(LoadBalancerProperties properties) {
        this.trustedProxies = parse(properties.proxy().trustedProxies());
        if (trustedProxies.isEmpty()) {
            log.info("No trusted proxies configured; X-Forwarded-For will be ignored and the "
                    + "TCP peer address used as the client IP");
        } else {
            log.info("Trusting X-Forwarded-For from {} proxy range(s): {}",
                    trustedProxies.size(), properties.proxy().trustedProxies());
        }
    }

    /**
     * @param request the inbound request
     * @return the client IP, or null if the peer address is unavailable (which happens for
     *         mock requests and for connections closed before the address is read)
     */
    public String resolve(ServerHttpRequest request) {
        String peer = peerAddress(request);
        if (peer == null) {
            return null;
        }
        if (!isTrusted(peer)) {
            return peer;
        }
        String forwardedFor = request.getHeaders().getFirst(X_FORWARDED_FOR);
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return peer;
        }
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String candidate = normalise(hops[i]);
            if (candidate == null) {
                continue;
            }
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        // Every hop was a trusted proxy: the original client is the leftmost entry.
        String leftmost = normalise(hops[0]);
        return leftmost != null ? leftmost : peer;
    }

    /** @return the raw TCP peer address, ignoring all headers. */
    public String peerAddress(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null) {
            return null;
        }
        InetAddress address = remote.getAddress();
        return address != null ? address.getHostAddress() : remote.getHostString();
    }

    /** @return true if {@code ip} falls inside any configured trusted-proxy range. */
    public boolean isTrusted(String ip) {
        if (trustedProxies.isEmpty()) {
            return false;
        }
        try {
            byte[] address = InetAddress.getByName(ip).getAddress();
            for (CidrRange range : trustedProxies) {
                if (range.contains(address)) {
                    return true;
                }
            }
        } catch (UnknownHostException ex) {
            // Not a literal IP. Never resolve it via DNS — that would let a header trigger
            // a lookup, which is both a latency amplifier and an SSRF-adjacent side channel.
            return false;
        }
        return false;
    }

    /**
     * Cleans one {@code X-Forwarded-For} entry: trims whitespace, strips the brackets and
     * port from {@code [::1]:443} forms, and rejects anything that is not a literal IP.
     * Rejecting rather than resolving is deliberate — a hostname here is either a
     * misconfiguration or an attempt to make the ALB perform a lookup on demand.
     */
    private static String normalise(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close > 0) {
                value = value.substring(1, close);
            }
        } else if (value.indexOf(':') == value.lastIndexOf(':') && value.indexOf(':') > 0) {
            // IPv4 with a port; a bare IPv6 address has multiple colons and no port here.
            value = value.substring(0, value.indexOf(':'));
        }
        try {
            // Canonicalised rather than returned verbatim. Two proxies may write the same IPv6
            // address differently ("2001:db8::1" vs "2001:0db8:0:0:0:0:0:1"); hash-based
            // affinity must treat those as one client, and it hashes this string.
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    private static List<CidrRange> parse(List<String> cidrs) {
        List<CidrRange> ranges = new ArrayList<>();
        if (cidrs == null) {
            return ranges;
        }
        for (String cidr : cidrs) {
            if (cidr == null || cidr.isBlank()) {
                continue;
            }
            ranges.add(CidrRange.parse(cidr.trim()));
        }
        return List.copyOf(ranges);
    }

    /**
     * A CIDR block, matched bitwise so it works for IPv4 and IPv6 alike.
     *
     * @param prefix    network bytes
     * @param prefixLen significant bits
     */
    record CidrRange(byte[] prefix, int prefixLen) {

        static CidrRange parse(String cidr) {
            String addressPart = cidr;
            int prefixLen = -1;
            int slash = cidr.indexOf('/');
            if (slash >= 0) {
                addressPart = cidr.substring(0, slash);
                try {
                    prefixLen = Integer.parseInt(cidr.substring(slash + 1).trim());
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Invalid trusted-proxy CIDR '" + cidr + "': bad prefix length");
                }
            }
            byte[] address;
            try {
                address = InetAddress.getByName(addressPart.trim()).getAddress();
            } catch (UnknownHostException ex) {
                throw new IllegalArgumentException("Invalid trusted-proxy entry '" + cidr
                        + "': not a literal IP address or CIDR block");
            }
            int maxBits = address.length * 8;
            if (prefixLen < 0) {
                prefixLen = maxBits; // a bare address is a /32 or /128
            }
            if (prefixLen > maxBits) {
                throw new IllegalArgumentException("Invalid trusted-proxy CIDR '" + cidr
                        + "': prefix length exceeds " + maxBits);
            }
            return new CidrRange(address, prefixLen);
        }

        boolean contains(byte[] address) {
            if (address.length != prefix.length) {
                // Do not attempt to compare IPv4 against IPv6; list both forms explicitly
                // if a dual-stack proxy needs to be trusted.
                return false;
            }
            int fullBytes = prefixLen / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != prefix[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLen % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (address[fullBytes] & mask) == (prefix[fullBytes] & mask);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CidrRange other
                    && prefixLen == other.prefixLen
                    && Arrays.equals(prefix, other.prefix);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(prefix) * 31 + prefixLen;
        }
    }
}
