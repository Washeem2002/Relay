package com.sluice.routing;

import com.sluice.auth.JwtVerifier;
import com.sluice.cache.CachePolicy;
import com.sluice.resilience.RetryBudget;
import com.sluice.resilience.RetryPolicy;
import com.sluice.schema.RouteConfig;

public record CompiledRoute(
        RouteConfig config,
        CompiledPool stable,
        CompiledPool canary,
        CachePolicy cachePolicy,
        JwtVerifier jwtVerifier,
        RetryPolicy retryPolicy,
        RetryBudget retryBudget) {

    public String name() {
        return config.name();
    }

    public String rewritePath(String rawPath) {
        if (!config.stripPrefix()) {
            return rawPath;
        }
        String remainder = PathNormalizer.stripSegments(rawPath, PathNormalizer.segments(config.pathPrefix()).size());
        String replacement = config.prefixReplacement() == null ? "" : config.prefixReplacement();
        if (replacement.endsWith("/") && remainder.startsWith("/")) {
            replacement = replacement.substring(0, replacement.length() - 1);
        }
        String result = replacement + remainder;
        return result.isEmpty() ? "/" : result;
    }
}
