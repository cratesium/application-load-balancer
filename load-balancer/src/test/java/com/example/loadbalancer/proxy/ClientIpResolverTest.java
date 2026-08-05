package com.example.loadbalancer.proxy;

import com.example.loadbalancer.config.LoadBalancerProperties;
import com.example.loadbalancer.model.LoadBalancingAlgorithm;
import com.example.loadbalancer.testsupport.TestBackends;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Client IP resolution and {@code X-Forwarded-For} trust tests.
 *
 * <p>This is a security boundary, not a formatting concern: with {@code IP_HASH} routing, a
 * spoofable client IP lets a caller choose which backend serves it and concentrate load on one
 * node. These tests pin the trust rules.
 */
class ClientIpResolverTest {

    private ClientIpResolver resolver(List<String> trustedProxies) {
        LoadBalancerProperties properties = TestBackends
                .propertiesBuilder(LoadBalancingAlgorithm.IP_HASH,
                        List.of(TestBackends.backendConfig("backend-1", "host-1", 8080, 1)))
                .trustedProxies(trustedProxies)
                .build();
        return new ClientIpResolver(properties);
    }

    private ServerHttpRequest request(String peer, String forwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest
                .get("http://alb.example.com/api/test")
                .remoteAddress(new java.net.InetSocketAddress(peer, 40000));
        if (forwardedFor != null) {
            builder = builder.header("X-Forwarded-For", forwardedFor);
        }
        return builder.build();
    }

    @Test
    @DisplayName("with no trusted proxies, X-Forwarded-For is ignored entirely")
    void ignoresForwardedForFromUntrustedPeer() {
        ClientIpResolver resolver = resolver(List.of());

        String clientIp = resolver.resolve(request("203.0.113.50", "10.9.9.9"));

        // The TCP peer address is the only value a client cannot forge, so it wins.
        assertThat(clientIp).isEqualTo("203.0.113.50");
    }

    @Test
    @DisplayName("a client cannot pick its own backend by forging the header")
    void forgedHeaderCannotChooseBackend() {
        ClientIpResolver resolver = resolver(List.of());

        // The same attacker trying several forged values always resolves to their real address,
        // so IP_HASH keeps sending them to the same backend rather than one of their choosing.
        for (String forged : List.of("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4")) {
            assertThat(resolver.resolve(request("198.51.100.7", forged))).isEqualTo("198.51.100.7");
        }
    }

    @Test
    @DisplayName("honours X-Forwarded-For from a trusted proxy")
    void honoursForwardedForFromTrustedProxy() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

        String clientIp = resolver.resolve(request("10.1.2.3", "203.0.113.9"));

        assertThat(clientIp).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("walks the chain right to left, skipping trusted hops")
    void walksChainRightToLeft() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

        // Chain: client, then two internal proxies. The right-hand entries were appended by
        // infrastructure we control; the left-hand ones are attacker-supplied.
        String clientIp = resolver.resolve(
                request("10.1.1.1", "203.0.113.9, 10.2.2.2, 10.3.3.3"));

        assertThat(clientIp).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("a prepended fake entry cannot displace the real client address")
    void resistsPrependedFakeEntries() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

        // The attacker sends "X-Forwarded-For: 9.9.9.9" and the trusted proxy appends their
        // real address. Right-to-left scanning finds the appended one first.
        String clientIp = resolver.resolve(request("10.1.1.1", "9.9.9.9, 203.0.113.44"));

        assertThat(clientIp).isEqualTo("203.0.113.44");
    }

    @Test
    @DisplayName("falls back to the leftmost entry when every hop is trusted")
    void fallsBackToLeftmostWhenAllHopsTrusted() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

        String clientIp = resolver.resolve(request("10.1.1.1", "10.5.5.5, 10.6.6.6"));

        assertThat(clientIp).isEqualTo("10.5.5.5");
    }

    @Test
    @DisplayName("strips ports from forwarded entries")
    void stripsPorts() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

        assertThat(resolver.resolve(request("10.1.1.1", "203.0.113.9:51234")))
                .isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(request("10.1.1.1", "[2001:db8::1]:443")))
                .isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    @DisplayName("rejects hostnames in the header rather than resolving them")
    void rejectsHostnamesInHeader() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

        // Resolving a header value via DNS would let a client make the ALB perform lookups
        // on demand. The entry is discarded and the peer address is used instead.
        assertThat(resolver.resolve(request("10.1.1.1", "evil.example.com")))
                .isEqualTo("10.1.1.1");
    }

    @Test
    @DisplayName("handles IPv6 trusted-proxy ranges")
    void supportsIpv6Cidrs() {
        ClientIpResolver resolver = resolver(List.of("2001:db8::/32"));

        assertThat(resolver.isTrusted("2001:db8::1")).isTrue();
        assertThat(resolver.isTrusted("2001:db9::1")).isFalse();
        assertThat(resolver.resolve(request("2001:db8::5", "203.0.113.1")))
                .isEqualTo("203.0.113.1");
    }

    @Test
    @DisplayName("CIDR boundaries are matched bitwise, not by prefix string")
    void matchesCidrBoundariesExactly() {
        ClientIpResolver resolver = resolver(List.of("192.168.1.0/25"));

        assertThat(resolver.isTrusted("192.168.1.0")).isTrue();
        assertThat(resolver.isTrusted("192.168.1.127")).isTrue();
        // 128 is outside a /25 even though the string prefix "192.168.1." matches.
        assertThat(resolver.isTrusted("192.168.1.128")).isFalse();
        assertThat(resolver.isTrusted("192.168.2.1")).isFalse();
    }

    @Test
    @DisplayName("a bare address in trusted-proxies means a single host")
    void bareAddressIsSingleHost() {
        ClientIpResolver resolver = resolver(List.of("10.1.2.3"));

        assertThat(resolver.isTrusted("10.1.2.3")).isTrue();
        assertThat(resolver.isTrusted("10.1.2.4")).isFalse();
    }

    @Test
    @DisplayName("does not match an IPv4 address against an IPv6 range")
    void doesNotMixAddressFamilies() {
        ClientIpResolver resolver = resolver(List.of("2001:db8::/32"));

        assertThat(resolver.isTrusted("10.0.0.1")).isFalse();
    }

    @Test
    @DisplayName("an invalid trusted-proxy entry fails fast at startup")
    void rejectsInvalidCidrAtStartup() {
        assertThatThrownBy(() -> resolver(List.of("not-an-ip/24")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid trusted-proxy");

        assertThatThrownBy(() -> resolver(List.of("10.0.0.0/99")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prefix length");
    }

    @Test
    @DisplayName("returns null when no peer address is available")
    void handlesMissingPeerAddress() {
        ClientIpResolver resolver = resolver(List.of());
        ServerHttpRequest noPeer = MockServerHttpRequest.get("http://alb/api").build();

        assertThat(resolver.resolve(noPeer)).isNull();
        assertThat(resolver.peerAddress(noPeer)).isNull();
    }

    @Test
    @DisplayName("an empty or malformed header falls back to the peer address")
    void handlesEmptyHeader() {
        ClientIpResolver resolver = resolver(List.of("10.0.0.0/8"));

        assertThat(resolver.resolve(request("10.1.1.1", ""))).isEqualTo("10.1.1.1");
        assertThat(resolver.resolve(request("10.1.1.1", " , , "))).isEqualTo("10.1.1.1");
    }
}
