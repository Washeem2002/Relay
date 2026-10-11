package com.sluice.gateway.middleware;

import com.sluice.gateway.GatewayException;
import com.sluice.gateway.RequestContext;
import com.sluice.observability.SluiceMetrics;
import com.sluice.ratelimit.RateLimitDecision;
import com.sluice.ratelimit.RateLimitService;
import com.sluice.routing.CompiledRoute;
import com.sluice.routing.RoutingSnapshot;
import com.sluice.schema.RateLimitPolicy;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RateLimitMiddleware {

    private final RateLimitService service;
    private final SluiceMetrics metrics;

    public RateLimitMiddleware(RateLimitService service, SluiceMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    public void apply(CompiledRoute route, Principal principal, RoutingSnapshot snapshot, RequestContext context,
                      HttpServletResponse response) {
        RateLimitPolicy global = snapshot.globalLimit();
        RateLimitPolicy plan = principal.tenant() == null ? null : principal.tenant().planPolicy();
        RateLimitPolicy routePolicy = route.config().rateLimit();
        if (global == null && plan == null && routePolicy == null) {
            return;
        }
        RateLimitService.Outcome outcome = service.evaluate(route.name(), principal.id(), global, plan, routePolicy);
        if (outcome.limiterUnavailable()) {
            if (!outcome.allowed()) {
                context.decide("ratelimit-fail-closed");
                throw new GatewayException(503, "rate_limiter_unavailable",
                        "rate limiting is unavailable and the gateway is configured to fail closed",
                        Map.of("Retry-After", "1"));
            }
            context.decide("ratelimit-fail-open");
            return;
        }
        RateLimitDecision decision = outcome.decision();
        if (!outcome.allowed()) {
            metrics.rateLimitRejected(route.name());
            context.decide("rate-limited");
            Map<String, String> headers = new LinkedHashMap<>(rateLimitHeaders(decision));
            headers.put("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            throw new GatewayException(429, "rate_limited",
                    "rate limit exceeded for " + decision.scope() + " scope", headers);
        }
        rateLimitHeaders(decision).forEach(response::setHeader);
    }

    private static Map<String, String> rateLimitHeaders(RateLimitDecision decision) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-RateLimit-Limit", String.valueOf(decision.limit()));
        headers.put("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        headers.put("X-RateLimit-Reset", String.valueOf(decision.resetEpochSeconds()));
        return headers;
    }
}
