package com.forvmom.MomentForeverAPIGateway.filter;

import com.forvmom.MomentForeverAPIGateway.util.JwtUtil; // your existing JWT utils (made reactive-friendly)
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";

    @Autowired
    private JwtUtil jwtUtil; // your existing JWT service (ensure methods are not blocking)

    /*
    ServerWebExchange is the reactive equivalent of the traditional
    HttpServletRequest and HttpServletResponse combined into one object.
    It represents the HTTP request-response interaction in a reactive application.
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. Check if route is public (via metadata)
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route != null) {
            Map<String, Object> metadata = route.getMetadata();
            if (metadata != null && Boolean.TRUE.equals(metadata.get("is-public"))) {
                // Public route – skip authentication
                return chain.filter(exchange);
            }
        }

        // 2. Extract JWT token from Authorization header
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            // No token – reject with 401
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        // 3. Validate token (this may be blocking – be cautious)
        try {
            if (!jwtUtil.validateToken(token)) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
        } catch (Exception e) {
            // Log error and return 401
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // 4. (Optional) Extract user info and forward to downstream services

        // 4. Extract user info and forward to downstream services
        String username = jwtUtil.extractUsername(token);
        Long userId = jwtUtil.extractUserId(token);
        exchange = exchange.mutate()
                .request(r -> r.header("X-User", username)
                        .header("X-User-Id", String.valueOf(userId))
                        .header("X-User-Roles", String.join(",", jwtUtil.extractRoles(token))))
                .build();

        // 5. Continue filter chain
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -1; // Ensures this runs before other filters
    }
}