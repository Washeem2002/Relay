package com.sluice.schema;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

public record RouteConfig(
        String name,
        String host,
        String pathPrefix,
        Set<String> methods,
        boolean stripPrefix,
        String prefixReplacement,
        String pool,
        int timeoutMs,
        RetryConfig retry,
        AuthConfig auth,
        RateLimitPolicy rateLimit,
        String cachePolicy,
        CanaryConfig canary) {

    public RouteConfig {
        if (host != null) {
            host = host.trim().toLowerCase(Locale.ROOT);
            if (host.isEmpty()) {
                host = null;
            }
        }
        TreeSet<String> normalized = new TreeSet<>();
        if (methods != null) {
            methods.forEach(method -> normalized.add(method.trim().toUpperCase(Locale.ROOT)));
        }
        methods = Collections.unmodifiableSortedSet(normalized);
        if (timeoutMs == 0) {
            timeoutMs = 30_000;
        }
        if (retry == null) {
            retry = RetryConfig.defaults();
        }
        if (auth == null) {
            auth = AuthConfig.none();
        }
    }

    public RouteConfig withCanary(CanaryConfig newCanary) {
        return new RouteConfig(name, host, pathPrefix, methods, stripPrefix, prefixReplacement, pool, timeoutMs,
                retry, auth, rateLimit, cachePolicy, newCanary);
    }
}
