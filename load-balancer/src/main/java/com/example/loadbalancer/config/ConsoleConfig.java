package com.example.loadbalancer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Serves the management console SPA from {@code classpath:/static/console/}.
 *
 * <p>Static assets (JS, CSS, images) are served directly. Any other path under
 * {@code /console/**} is forwarded to {@code index.html} so that the React Router
 * can handle client-side routing.
 */
@Configuration
public class ConsoleConfig {

    @Bean
    public RouterFunction<ServerResponse> consoleRoutes(
            @Value("${load-balancer.console.path-prefix:/console}") String prefix) {

        Resource indexHtml = new ClassPathResource("static/console/index.html");

        return RouterFunctions.route()
                // Serve static assets from /console/assets/**
                .resources(prefix + "/assets/**", new ClassPathResource("static/console/assets/"))
                // Serve other static files (favicon, manifest, etc.) from /console/*
                .resources(prefix + "/*", new ClassPathResource("static/console/"))
                // SPA fallback: any deeper /console/** path returns index.html
                .GET(prefix, request -> ServerResponse.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .bodyValue(indexHtml))
                .GET(prefix + "/", request -> ServerResponse.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .bodyValue(indexHtml))
                .GET(prefix + "/**", request -> {
                    // If the path has a file extension, it's probably a missing static file → 404
                    String path = request.path();
                    if (path.contains(".") && !path.endsWith(".html")) {
                        return ServerResponse.notFound().build();
                    }
                    // Otherwise, return index.html for client-side routing
                    return ServerResponse.ok()
                            .contentType(MediaType.TEXT_HTML)
                            .bodyValue(indexHtml);
                })
                .build();
    }
}
