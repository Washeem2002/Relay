package com.sluice.routing;

import com.sluice.auth.TenantDirectory;
import com.sluice.schema.ConfigDocument;
import com.sluice.schema.RateLimitPolicy;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public record RoutingSnapshot(
        long version,
        Instant compiledAt,
        RouteMatcher matcher,
        Map<String, CompiledRoute> routes,
        Map<String, CompiledPool> pools,
        TenantDirectory tenants,
        RateLimitPolicy globalLimit,
        ConfigDocument document) {

    public static RoutingSnapshot empty() {
        return new RoutingSnapshot(0, Instant.EPOCH, RouteMatcher.build(java.util.List.of()), Map.of(), Map.of(),
                TenantDirectory.empty(), null, ConfigDocument.empty());
    }

    public Optional<CompiledRoute> match(String host, String path, String method) {
        return matcher.match(host, path, method).map(route -> routes.get(route.name()));
    }
}
