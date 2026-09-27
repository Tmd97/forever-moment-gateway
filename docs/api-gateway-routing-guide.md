# API Gateway Routing Guide (Beginner to Architecture Level)

This document explains how routing works in this project’s API Gateway, how services are discovered and load-balanced, what is currently implemented, and what enhancements should be considered next.

Repository context:
- Gateway app: `gateway-main`
- Main config: `src/main/resources/application.yml`
- JWT filter: `src/main/java/com/forvmom/MomentForeverAPIGateway/filter/JwtAuthenticationFilter.java`
- Request log filter: `src/main/java/com/forvmom/MomentForeverAPIGateway/filter/GatewayRequestLoggingFilter.java`
- Discovery/config bean: `src/main/java/com/forvmom/MomentForeverAPIGateway/config/ConsulConfig.java`

---

## 1) What an API Gateway does in this project

The gateway is the single entry point for client applications. Instead of calling Platform/Booking/Payment services directly, clients call the gateway, and the gateway forwards requests to the right downstream service.

In this codebase, gateway responsibilities are:
- Route requests by URL path.
- Apply authentication rules (public vs protected routes).
- Forward requests using service discovery (`lb://SERVICE-NAME`).
- Provide central observability (request logs and route-level logs).
- Aggregate Swagger API docs from multiple services.

The app is a Spring Cloud Gateway app:
- `@SpringBootApplication`
- `@EnableDiscoveryClient`
- runs on `server.port: 8086`

---

## 2) How routing works (step by step)

Routing is defined under:
- `spring.cloud.gateway.routes` in `application.yml`.

Each route has:
1. `id` - unique route name.
2. `uri` - destination service (`lb://...` means discover via Consul + client-side load balancing).
3. `predicates` - URL pattern matcher (`Path=...`).
4. `filters` - path transformers (here, mainly `StripPrefix=1`).
5. `metadata` - custom flags used by filters (`is-public` / `requires-auth`).

### Example route

```yaml
- id: platform-public
  uri: lb://MOMENT-FOREVER-PLATFORM
  predicates:
    - Path=/api/platform/public/**
  filters:
    - StripPrefix=1
  metadata:
    is-public: true
```

How this behaves:
- Incoming: `/api/platform/public/images/fetch/abc.jpg`
- `Path` matches this route.
- `StripPrefix=1` removes first segment `/api`.
- Forwarded downstream path becomes: `/platform/public/images/fetch/abc.jpg` (assuming downstream service has `/platform` context path).

### Why `StripPrefix=1` matters

All routes in this gateway are designed with `/api/...` externally.  
The first segment (`/api`) is removed before forwarding, so downstream services don’t need to include `/api` in their own controller mappings.

---

## 3) Public vs protected request flow

Authentication behavior is implemented in `JwtAuthenticationFilter` (a `GlobalFilter`).

High-level logic:
1. Read matched route metadata.
2. If route metadata has `is-public: true`, skip JWT validation.
3. Otherwise require `Authorization: Bearer <token>`.
4. Validate token using `JwtUtil`.
5. If valid, forward with user headers.
6. If missing/invalid token, return `401`.

### Important detail

Security is route-metadata-driven, not controller-annotation-driven at gateway level.  
If a route that should be protected is accidentally marked public (or missing metadata conventions), auth behavior changes immediately.

---

## 4) How to confirm requests are actually hitting the gateway

`GatewayRequestLoggingFilter` logs one line per request:
- HTTP method
- request path + query
- matched `routeId`
- target service URI
- response status
- duration in milliseconds

Sample log style:

```text
GATEWAY_REQUEST method=GET path=/api/platform/public/images/fetch/abc.jpg routeId=platform-public target=lb://MOMENT-FOREVER-PLATFORM status=200 durationMs=24 signal=onComplete
```

If `routeId=NO_ROUTE_MATCHED`, the request reached gateway but did not match any configured path predicate.

---

## 5) How service registration and discovery work

Consul integration is configured in `application.yml`:
- `spring.cloud.consul.discovery.enabled: true`
- `register: true`
- `service-name: ${spring.application.name}` (`api-gateway`)
- health checks via `/actuator/health`

What this means:
1. Gateway registers itself in Consul.
2. Downstream services (Platform/Booking/Payment) should also register in Consul.
3. Gateway routes use logical names (`lb://MOMENT-FOREVER-PLATFORM`) instead of fixed host:port.
4. Spring Cloud LoadBalancer resolves service instances from Consul at runtime.

---

## 6) How load balancing happens here

Load balancing is client-side:
- `lb://SERVICE-NAME` triggers Spring Cloud LoadBalancer.
- LoadBalancer picks one healthy instance from discovered instances.
- If multiple instances exist, traffic is distributed across them.

Related config:
- `spring.cloud.loadbalancer.cache.ttl: 5s` (instance list cache TTL).

Practical interpretation:
- Gateway refreshes service-instance view frequently.
- It can adapt relatively quickly when instances go up/down.

---

## 7) Current route map (conceptual)

Public:
- `/api/platform/public/**`
- `/api/platform/auth/**`
- `/api/uploads/**`
- `/api/*/v3/api-docs/**`
- specific actuator health endpoints

Protected:
- `/api/platform/admin/**`
- `/api/platform/user/**`
- `/api/platform/**` (catch-all platform protected)
- `/api/booking/**`
- `/api/payment/**`
- actuator non-health paths for services

Design note: route order matters when patterns overlap. Keep specific routes before broad catch-all routes.

---

## 8) Recent URL behavior alignment (important)

For image links generated by platform APIs, the expected public path shape for gateway usage is:
- `/api/platform/public/images/fetch/{storageFileName}`

Why:
- Gateway only routes configured `/api/...` paths.
- Bare `/public/...` is not a gateway route.

This is why generated URLs should align to gateway route prefixes in distributed deployments.

---

## 9) Recommended enhancements (senior/architecture view)

Below are high-value improvements for production maturity.

## 9.1 Security hardening
- Move JWT secret out of repo config into secret manager (Vault/AWS Secrets Manager/Azure Key Vault).
- Add token issuer/audience checks in gateway validation policy.
- Add route-level authorization (roles/scopes), not only authentication.
- Add explicit allow-list for forwarded headers; sanitize incoming `X-Forwarded-*`/identity headers.
- Add rate limiting per route/client (Redis-backed token bucket).

## 9.2 Reliability and resilience
- Add timeouts, retries, and circuit breakers per route (Resilience4j + route config).
- Add fallback responses for critical reads where appropriate.
- Add bulkheads for noisy downstream dependencies.
- Distinguish idempotent retry-safe routes from non-idempotent ones.

## 9.3 Observability and operations
- Add correlation ID generation/propagation (`X-Correlation-Id`) and include in all logs.
- Add structured JSON logging for production ingestion (ELK/Datadog/Splunk).
- Add metrics dashboards: route latency percentiles, 4xx/5xx by route, upstream error ratios.
- Add distributed tracing (OpenTelemetry) from gateway to services.

## 9.4 Configuration and maintainability
- Externalize route config per environment (non-hardcoded route definitions for scale).
- Introduce route-contract tests to catch accidental route breakage.
- Add config validation startup checks (missing metadata/overlapping routes).
- Standardize metadata keys (`is-public`, `requires-auth`) and enforce conventions.

## 9.5 Performance
- Review JWT validation path to ensure non-blocking execution in reactive pipeline.
- Use selective body/header logging to avoid expensive payload logs.
- Tune load balancer cache TTL based on service churn and Consul update behavior.

## 9.6 Edge delivery
- Consider CDN/WAF in front of gateway for static/media, bot filtering, and DDoS mitigation.
- For large file/media paths, validate whether direct object/CDN links are better than service streaming.

---

## 10) Known gaps / code-quality observations to track

These are not blockers for understanding, but good backlog candidates:

1. `JwtAuthenticationFilter` currently sets `X-User-Id` header twice (once with username, once with userId).  
   Use distinct headers (e.g., `X-Username`, `X-User-Id`) to avoid overwriting/confusion.

2. JWT exception handling returns `401` but does not include explicit structured error body/log context.  
   Add standardized error payload + trace ID for easier client debugging.

3. Ensure all services use consistent path conventions (`/api/...` externally, service context internally) to avoid URL drift.

---

## 11) Beginner Q/A (practical understanding)

### Q1. Why do we use `lb://SERVICE-NAME` instead of `http://host:port`?
Because service instances are dynamic (scale up/down, restart, move hosts). Discovery + load balancing removes hardcoded endpoints.

### Q2. What decides whether a request is public or protected?
Gateway route metadata (`is-public`) evaluated by `JwtAuthenticationFilter`.

### Q3. Why does `/public/...` fail on gateway?
Because gateway routes are configured under `/api/...`; `/public/...` does not match any route predicate.

### Q4. What does `StripPrefix=1` do?
It removes `/api` from incoming path before forwarding downstream.

### Q5. How do I know a request reached the gateway?
Check `GATEWAY_REQUEST` log lines in console or `logs/gateway.log`.

### Q6. If gateway returns 401, does downstream service still get the request?
No. Gateway short-circuits protected requests before forwarding when JWT is missing/invalid.

### Q7. Where is load balancing algorithm configured?
Spring Cloud LoadBalancer defaults are used unless custom strategy is added. Current config mostly tunes instance cache TTL.

### Q8. Can route order break behavior?
Yes. Broad patterns can swallow specific ones if placed earlier.

### Q9. Do we need both gateway and service-level security?
Yes. Gateway is the first gate, but service-level security is still important for defense in depth.

### Q10. Should image/media URLs be absolute or relative?
Either can work, but they must align with deployment entrypoint. If clients call gateway host, response URLs should be gateway-compatible.

---

## 12) Senior/Architecture Q/A (future implementation planning)

### Q1. Should we keep static YAML routes or move to dynamic route definitions?
For small stable systems, YAML is fine. For larger fleets, dynamic route sources + strong validation reduce operational friction.

### Q2. Where should authentication end and authorization begin?
Gateway can perform coarse authN/authZ; fine-grained domain authorization should remain in services.

### Q3. Should JWT validation happen at gateway only?
Prefer gateway validation plus selective downstream verification based on trust boundaries and threat model.

### Q4. How do we prevent cross-service identity spoofing?
Sign/verify internal identity headers, use mTLS between gateway and services, and reject client-supplied identity headers.

### Q5. Which routes get retries and which should not?
Retry only idempotent reads and clearly safe operations; never blindly retry payment/booking write paths.

### Q6. How to support zero-downtime service deployments?
Use health checks, readiness gates, short LB cache TTL, and gradual rollout (canary/blue-green) with route weighting where possible.

### Q7. How do we enforce consistent error contracts across services?
Adopt shared error schema and enforce via gateway transforms or platform standards/tests.

### Q8. Should API docs be aggregated in gateway long-term?
Good for developer UX; ensure auth boundaries and environment-specific doc exposure are controlled.

### Q9. What should be our minimum gateway SLOs?
Define p95/p99 latency budgets, availability targets, and error-rate thresholds per critical route group.

### Q10. How do we scale gateway safely?
Horizontal scale gateway instances, keep filters non-blocking, avoid heavy per-request sync work, and monitor queue/event-loop pressure.

---

## 13) Suggested implementation roadmap

1. **Security baseline**: secret externalization, header sanitization, correlation IDs.
2. **Resilience baseline**: route timeouts + retries (safe routes only) + circuit breakers.
3. **Observability baseline**: structured logs + metrics dashboards + tracing.
4. **Governance baseline**: route tests + config validation checks in CI.
5. **Advanced traffic**: canary/weighted routing and per-route policy packs.

This order gives the fastest risk reduction and operational clarity for growing teams.

