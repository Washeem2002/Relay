package com.sluice.ratelimit;

import com.sluice.config.SluiceProperties;
import com.sluice.observability.SluiceMetrics;
import com.sluice.schema.RateLimitPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitService.class);

    public record Outcome(boolean allowed, RateLimitDecision decision, boolean limiterUnavailable) {
    }

    private final RateLimiter limiter;
    private final SluiceProperties properties;
    private final SluiceMetrics metrics;

    public RateLimitService(RateLimiter limiter, SluiceProperties properties, SluiceMetrics metrics) {
        this.limiter = limiter;
        this.properties = properties;
        this.metrics = metrics;
    }

    public Outcome evaluate(String routeName, String tenantKey, RateLimitPolicy global, RateLimitPolicy tenantPlan,
                            RateLimitPolicy tenantRoute) {
        String prefix = properties.rateLimit().keyPrefix();
        try {
            RateLimitDecision tightest = null;
            if (global != null) {
                RateLimitDecision decision = limiter.check(prefix + "global", global, "global");
                if (!decision.allowed()) {
                    return new Outcome(false, decision, false);
                }
                tightest = tighter(tightest, decision);
            }
            if (tenantPlan != null) {
                RateLimitDecision decision = limiter.check(prefix + "tenant:" + tenantKey, tenantPlan, "tenant");
                if (!decision.allowed()) {
                    return new Outcome(false, decision, false);
                }
                tightest = tighter(tightest, decision);
            }
            if (tenantRoute != null) {
                RateLimitDecision decision =
                        limiter.check(prefix + "route:" + routeName + ":" + tenantKey, tenantRoute, "tenant-route");
                if (!decision.allowed()) {
                    return new Outcome(false, decision, false);
                }
                tightest = tighter(tightest, decision);
            }
            return new Outcome(true, tightest, false);
        } catch (RuntimeException e) {
            metrics.rateLimiterError();
            boolean failOpen = properties.rateLimit().failMode() == SluiceProperties.FailMode.OPEN;
            LOG.warn("rate limiter unavailable, failing {}: {}", failOpen ? "open" : "closed", e.toString());
            return new Outcome(failOpen, null, true);
        }
    }

    private static RateLimitDecision tighter(RateLimitDecision current, RateLimitDecision candidate) {
        if (current == null || candidate.remaining() < current.remaining()) {
            return candidate;
        }
        return current;
    }
}
