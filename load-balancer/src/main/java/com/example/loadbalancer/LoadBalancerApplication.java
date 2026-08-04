package com.example.loadbalancer;

import com.example.loadbalancer.config.LoadBalancerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Application Load Balancer.
 *
 * <p>The application is split into a <em>data plane</em> (everything under
 * {@code proxy} and {@code routing}, which touches every request) and a
 * <em>control plane</em> (everything under {@code controller}, {@code health}
 * and {@code config}, which mutates shared state occasionally). The data plane
 * never takes a lock; the control plane publishes immutable snapshots that the
 * data plane reads through {@code AtomicReference}.
 */
@SpringBootApplication
@EnableConfigurationProperties(LoadBalancerProperties.class)
@EnableScheduling
public class LoadBalancerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoadBalancerApplication.class, args);
    }
}
