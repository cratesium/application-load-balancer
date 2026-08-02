package com.example.loadbalancer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;

/**
 * Applies {@code load-balancer.listen} and {@code load-balancer.limits} to the Netty server.
 *
 * <h2>Why the listen address lives under load-balancer, not server</h2>
 * The ALB's listening socket is a first-class part of its configuration, not an incidental
 * Spring Boot detail. Keeping it in the same block as the backends makes the topology readable
 * in one place, which matters when the whole point of the file is "traffic arrives here and goes
 * there".
 *
 * <h2>The decoder limits are a security control</h2>
 * {@code maxHeaderSize} and {@code maxInitialLineLength} are enforced by Netty's HTTP decoder,
 * before a single byte reaches application code. That placement is the point: a request with a
 * 500MB header cannot be rejected by application-level validation, because the memory is
 * consumed while the header is still being parsed. Netty aborts the connection at the limit,
 * which is the only place this class of attack can be stopped cheaply.
 */
@Configuration(proxyBeanMethods = false)
public class ServerConfig {

    private static final Logger log = LoggerFactory.getLogger(ServerConfig.class);

    /**
     * Binds the configured listen address and installs the decoder limits.
     *
     * <p>{@code idleTimeout} closes connections that go quiet. Without it, a client can open
     * thousands of connections, send nothing, and hold server resources indefinitely — the
     * Slowloris pattern. Note that this is a mitigation, not immunity; see the README's
     * production-limitations section for what a real edge deployment still needs in front.
     */
    @Bean
    public WebServerFactoryCustomizer<NettyReactiveWebServerFactory> listenAddressCustomizer(
            LoadBalancerProperties properties) {
        return factory -> {
            LoadBalancerProperties.Listen listen = properties.listen();
            LoadBalancerProperties.Limits limits = properties.limits();

            factory.setAddress(new InetSocketAddress(listen.host(), listen.port()).getAddress());
            factory.setPort(listen.port());

            factory.addServerCustomizers(httpServer -> httpServer
                    .httpRequestDecoder(decoder -> decoder
                            .maxHeaderSize((int) limits.maxHeaderSize().toBytes())
                            .maxInitialLineLength((int) limits.maxInitialLineLength().toBytes()))
                    .idleTimeout(properties.timeouts().idle()));

            log.info("Load balancer listening on {}:{} (max header {}, max initial line {}, idle timeout {})",
                    listen.host(), listen.port(), limits.maxHeaderSize(),
                    limits.maxInitialLineLength(), properties.timeouts().idle());
        };
    }
}
