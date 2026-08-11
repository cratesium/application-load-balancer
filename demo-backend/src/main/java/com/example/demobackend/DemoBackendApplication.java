package com.example.demobackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A deliberately small HTTP service used to make load balancer behaviour observable.
 *
 * <p>Every response names the server that produced it, so a loop of curls shows the
 * distribution directly. It also exposes endpoints that misbehave on purpose — slow, failing,
 * togglable health — because you cannot demonstrate least-connections routing, passive health
 * checking or a circuit breaker against backends that always work.
 */
@SpringBootApplication
public class DemoBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoBackendApplication.class, args);
    }
}
